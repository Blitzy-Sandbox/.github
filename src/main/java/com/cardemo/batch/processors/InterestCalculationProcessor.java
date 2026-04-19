/*
 * InterestCalculationProcessor.java
 *
 * Spring Batch ItemProcessor for the interest calculation pipeline, migrated from
 * COBOL program CBACT04C.cbl (652 lines) and JCL job INTCALC.jcl.
 *
 * This processor reads TransactionCategoryBalance records sequentially and computes
 * monthly interest using the exact COBOL formula: (balance × rate) / 1200, with
 * BigDecimal precision and RoundingMode.HALF_EVEN (banker's rounding).
 *
 * COBOL Traceability:
 * - CBACT04C.cbl — Interest calculation batch program (main logic)
 * - INTCALC.jcl  — JCL job definition (EXEC PGM=CBACT04C,PARM='2022071800')
 * - CVTRA01Y.cpy — Transaction category balance record layout (input)
 * - CVTRA02Y.cpy — Disclosure group record layout (interest rates)
 * - CVTRA05Y.cpy — Transaction record layout (output)
 * - CVACT01Y.cpy — Account record layout
 * - CVACT03Y.cpy — Card cross-reference record layout (CXACAIX alternate index)
 *
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 * Licensed under the Apache License, Version 2.0
 */
package com.cardemo.batch.processors;

import com.cardemo.domain.rules.InterestCalculationRules;
import com.cardemo.exception.CardDemoException;
import com.cardemo.model.entity.Account;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.model.entity.DisclosureGroup;
import com.cardemo.model.entity.Transaction;
import com.cardemo.model.entity.TransactionCategoryBalance;
import com.cardemo.model.key.TransactionCategoryBalanceId;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CardCrossReferenceRepository;
import com.cardemo.repository.DisclosureGroupRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.annotation.AfterStep;
import org.springframework.batch.core.annotation.BeforeStep;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Interest calculation batch processor implementing the business logic from CBACT04C.cbl.
 *
 * <p>Processes {@link TransactionCategoryBalance} records sequentially and produces
 * system-generated interest {@link Transaction} records. Implements account break
 * detection to accumulate interest across category balance records belonging to the
 * same account before applying the total to the account balance.</p>
 *
 * <h3>Processing Pipeline (per TCATBAL record)</h3>
 * <ol>
 *   <li>Account break detection — when account ID changes, update previous account
 *       with accumulated interest and load new account/XREF data</li>
 *   <li>Interest rate lookup — 2-attempt pattern: account-specific group first,
 *       then DEFAULT fallback (← CBACT04C 1200-GET-INTEREST-RATE)</li>
 *   <li>Interest computation — exact formula: {@code (balance × rate) / 1200}
 *       with {@code RoundingMode.HALF_EVEN} (← CBACT04C 1300-COMPUTE-INTEREST)</li>
 *   <li>Transaction generation — system-generated interest transaction
 *       (← CBACT04C 1300-B-WRITE-TX)</li>
 * </ol>
 *
 * <h3>Key Design Decisions</h3>
 * <ul>
 *   <li>All financial fields use {@link BigDecimal} — zero float/double per AAP §0.8.2</li>
 *   <li>{@code RoundingMode.HALF_EVEN} (banker's rounding) matches COBOL default</li>
 *   <li>Formula preserved without algebraic simplification per AAP §0.8.5</li>
 *   <li>DEFAULT group fallback matches COBOL 1200-A-GET-DEFAULT-INT-RATE exactly</li>
 *   <li>{@code @StepScope} ensures a fresh instance per step execution, providing
 *       thread safety for the mutable account-break detection state fields
 *       ({@code currentAccountId}, {@code accountBalance}, etc.). This mirrors
 *       the COBOL CBACT04C single-threaded working storage model.</li>
 * </ul>
 *
 * <h3>Layered Architecture (Post-Refactoring)</h3>
 * <p>Per AAP §0.4.3 "Domain Rules Pattern" and §0.7.5 "Extraction Safety Matrix",
 * the pure, stateless, framework-independent portions of the interest-calculation
 * business logic (the formula, the 2-attempt rate-selection fallback, the in-memory
 * account-update, and the non-zero-interest guard) are delegated to the domain layer
 * via {@link InterestCalculationRules}. The processor itself retains ONLY the
 * Spring Batch lifecycle integration, repository I/O, stream-processing state
 * (account-break accumulators, transaction-ID sequencing), and transaction
 * generation — all concerns that must remain in the batch layer per AAP §0.7.5.</p>
 *
 * @see TransactionCategoryBalance
 * @see Transaction
 * @see DisclosureGroup
 * @see InterestCalculationRules
 * @see <a href="https://github.com/aws-samples/carddemo/blob/27d6c6f/app/cbl/CBACT04C.cbl">
 *      CBACT04C.cbl — Interest calculation COBOL program</a>
 * @see <a href="https://github.com/aws-samples/carddemo/blob/27d6c6f/app/jcl/INTCALC.jcl">
 *      INTCALC.jcl — Interest calculation JCL job</a>
 */
@Component
@StepScope
public class InterestCalculationProcessor
        implements ItemProcessor<TransactionCategoryBalance, Transaction> {

    private static final Logger log = LoggerFactory.getLogger(InterestCalculationProcessor.class);

    /**
     * Fixed transaction type code for system-generated interest transactions.
     * COBOL: {@code MOVE '01' TO TRAN-TYPE-CD} (← 1300-B-WRITE-TX)
     */
    private static final String INTEREST_TRAN_TYPE_CD = "01";

    /**
     * Fixed transaction category code for interest transactions.
     * COBOL: {@code MOVE '05' TO TRAN-CAT-CD} (← 1300-B-WRITE-TX, PIC 9(04) → Short 5)
     */
    private static final short INTEREST_TRAN_CAT_CD = 5;

    /**
     * Fixed transaction source for system-generated transactions.
     * COBOL: {@code MOVE 'System' TO TRAN-SOURCE} (← 1300-B-WRITE-TX)
     */
    private static final String INTEREST_TRAN_SOURCE = "System";

    // ========================================================================
    // Injected Repositories and Domain Rules (constructor injection)
    // ========================================================================

    private final DisclosureGroupRepository disclosureGroupRepository;
    private final CardCrossReferenceRepository cardCrossReferenceRepository;
    private final AccountRepository accountRepository;

    /**
     * Domain rules class encapsulating pure interest-calculation business logic.
     *
     * <p>Per AAP §0.4.3 "Domain Rules Pattern" and §0.7.5 "Extraction Safety Matrix",
     * the following COBOL-derived logic is delegated to this stateless rules class:</p>
     * <ul>
     *   <li>Monthly interest formula {@code (balance × rate) / 1200} with scale-2
     *       HALF_EVEN rounding (← CBACT04C.cbl paragraph 1300-COMPUTE-INTEREST)</li>
     *   <li>Rate selection with DEFAULT fallback (← 1200-GET-INTEREST-RATE and
     *       1200-A-GET-DEFAULT-INT-RATE)</li>
     *   <li>In-memory account balance update and cycle-counter reset
     *       (← 1050-UPDATE-ACCOUNT — persistence remains the processor's
     *       responsibility)</li>
     *   <li>Non-zero-interest guard predicate for the in-memory account update
     *       (← source {@code updateAccount()} line 542 guard)</li>
     * </ul>
     *
     * <p>The rules class exposes the public constants {@code DIVISOR} (the 1200
     * monthly-interest divisor) and {@code DEFAULT_GROUP_ID} (the "DEFAULT"
     * disclosure-group identifier) as a single source of truth per AAP §0.4.3
     * "Constant Holder Pattern"; this processor references them via the rules
     * class rather than re-declaring them locally.</p>
     */
    private final InterestCalculationRules interestCalculationRules;

    // ========================================================================
    // State Fields (← COBOL WORKING-STORAGE SECTION, lines 100-135)
    // ========================================================================

    /**
     * Processing date passed from JCL EXEC PARM (← LINKAGE SECTION PARM-DATE PIC X(10)).
     * Used for generating transaction IDs in format: {parmDate}-{suffix}.
     */
    private String parmDate;

    /**
     * Auto-incrementing suffix for generated transaction IDs.
     * COBOL: WS-TRANID-SUFFIX PIC 9(06) — migrated to AtomicInteger for thread safety.
     */
    private final AtomicInteger tranIdSuffix = new AtomicInteger(0);

    /**
     * Last processed account number for break detection.
     * COBOL: WS-LAST-ACCT-NUM PIC X(11).
     */
    private String lastAcctNum = "";

    /**
     * Accumulated total interest for the current account across all category balances.
     * COBOL: WS-TOTAL-INT PIC S9(9)V99 COMP-3 — BigDecimal with scale 2.
     */
    private BigDecimal totalInterest = BigDecimal.ZERO;

    /**
     * Cached current account record (← 1100-GET-ACCT-DATA).
     */
    private Account currentAccount;

    /**
     * Cached current cross-reference record (← 1110-GET-XREF-DATA via CXACAIX).
     */
    private CardCrossReference currentXref;

    // ========================================================================
    // Constructor
    // ========================================================================

    /**
     * Constructs the interest calculation processor with required repository
     * and domain-rules dependencies.
     *
     * <p>All three repositories are constructor-injected by Spring's dependency injection
     * framework, mirroring the COBOL FILE-CONTROL declarations for DISCGRP, XREFFILE,
     * and ACCTFILE in CBACT04C.cbl. The {@link InterestCalculationRules} domain-rules
     * bean encapsulates the pure business logic extracted from this processor per
     * AAP §0.4.3 "Domain Rules Pattern".</p>
     *
     * @param disclosureGroupRepository    repository for interest rate disclosure groups
     *                                     (← DISCGRP indexed random access)
     * @param cardCrossReferenceRepository repository for card-to-account cross-references
     *                                     (← XREFFILE indexed random access, CXACAIX AIX)
     * @param accountRepository            repository for account data read/update
     *                                     (← ACCTFILE indexed random access)
     * @param interestCalculationRules     domain-rules bean providing the pure
     *                                     interest-calculation business logic (formula,
     *                                     rate selection, account update, guard predicate)
     */
    public InterestCalculationProcessor(
            DisclosureGroupRepository disclosureGroupRepository,
            CardCrossReferenceRepository cardCrossReferenceRepository,
            AccountRepository accountRepository,
            InterestCalculationRules interestCalculationRules) {
        this.disclosureGroupRepository = disclosureGroupRepository;
        this.cardCrossReferenceRepository = cardCrossReferenceRepository;
        this.accountRepository = accountRepository;
        this.interestCalculationRules = interestCalculationRules;
    }

    // ========================================================================
    // Spring Batch Step Lifecycle Hooks
    // ========================================================================

    /**
     * Initializes processor state before step execution begins.
     *
     * <p>Resets all WORKING-STORAGE equivalent fields to their initial values,
     * ensuring clean state for each step execution. Optionally reads the parmDate
     * from Spring Batch job parameters if not already set via {@link #setParmDate(String)}.</p>
     *
     * @param stepExecution the current step execution context
     */
    @BeforeStep
    public void beforeStep(StepExecution stepExecution) {
        lastAcctNum = "";
        totalInterest = BigDecimal.ZERO;
        tranIdSuffix.set(0);
        currentAccount = null;
        currentXref = null;

        // Read parmDate from job parameters if not explicitly set via setParmDate().
        // Maps COBOL LINKAGE SECTION PARM-DATE read from JCL EXEC PARM=...
        String dateParam = stepExecution.getJobParameters().getString("parmDate");
        if (dateParam != null && parmDate == null) {
            this.parmDate = dateParam;
        }

        // Fallback to current date if parmDate is still null — ensures generated
        // transaction IDs always have a valid date prefix (format YYYY-MM-DD)
        // instead of the string "null". The COBOL JCL always provides a PARM date;
        // the Java fallback handles the case where the job is launched without it.
        if (this.parmDate == null) {
            this.parmDate = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
            log.warn("No parmDate provided — defaulting to current date: {}", this.parmDate);
        }
        log.info("Interest calculation processor initialized — parmDate: {}", parmDate);
    }

    /**
     * Flushes the last account's accumulated interest after step execution completes.
     *
     * <p>Maps the COBOL post-loop logic in CBACT04C.cbl where, after the TCATBAL
     * read loop exits at END-OF-FILE, a final call to 1050-UPDATE-ACCOUNT applies
     * the accumulated interest to the last processed account:</p>
     * <pre>
     * IF WS-FIRST-TIME NOT = 'Y'
     *     PERFORM 1050-UPDATE-ACCOUNT
     * END-IF
     * </pre>
     *
     * @param stepExecution the completed step execution context
     * @return the existing exit status, unchanged
     */
    @AfterStep
    public ExitStatus afterStep(StepExecution stepExecution) {
        if (!lastAcctNum.isEmpty()) {
            log.info("Flushing final account update for account: {}", lastAcctNum);
            updateAccount();
        }
        log.info("Interest calculation step complete — transactions generated: {}",
                tranIdSuffix.get());
        return stepExecution.getExitStatus();
    }

    // ========================================================================
    // Core Processing Logic (← CBACT04C PROCEDURE DIVISION, lines 170-320)
    // ========================================================================

    /**
     * Processes a single {@link TransactionCategoryBalance} record through the interest
     * calculation pipeline.
     *
     * <p>Implements the main loop body from CBACT04C.cbl lines 170-320:</p>
     * <ol>
     *   <li>Extract account ID from composite key for break detection</li>
     *   <li>On account break: update previous account, reset accumulators, load new
     *       account/XREF data</li>
     *   <li>Look up interest rate with DEFAULT fallback</li>
     *   <li>Compute monthly interest if rate is non-zero</li>
     *   <li>Generate system interest transaction</li>
     * </ol>
     *
     * @param item the transaction category balance record to process
     * @return a system-generated interest {@link Transaction}, or {@code null} if the
     *         interest rate is zero (record skipped per COBOL logic)
     * @throws Exception if a fatal error occurs during processing (e.g., missing account
     *                   or missing disclosure group record)
     */
    @Override
    public Transaction process(TransactionCategoryBalance item) throws Exception {
        TransactionCategoryBalanceId key = item.getId();
        String acctId = key.getAcctId();
        String typeCode = key.getTypeCode();
        Short catCode = key.getCatCode();

        // Account break detection (← CBACT04C lines 260-310)
        if (!acctId.equals(lastAcctNum)) {
            // If not the first account, update the previous account's balance
            if (!lastAcctNum.isEmpty()) {
                updateAccount();
            }

            // Reset accumulated interest for the new account (← MOVE 0 TO WS-TOTAL-INT)
            totalInterest = BigDecimal.ZERO;
            lastAcctNum = acctId;

            // Load account data (← 1100-GET-ACCT-DATA)
            currentAccount = accountRepository.findById(acctId)
                    .orElseThrow(() -> new CardDemoException(
                            "Account not found with ID: " + acctId));
            log.info("Account break detected — now processing account: {}", acctId);

            // Load cross-reference data (← 1110-GET-XREF-DATA via CXACAIX alternate index)
            List<CardCrossReference> xrefs = cardCrossReferenceRepository
                    .findByXrefAcctId(acctId);
            currentXref = xrefs.isEmpty() ? null : xrefs.getFirst();
            if (currentXref == null) {
                log.debug("No cross-reference record found for account: {}", acctId);
            }
        }

        // Interest rate lookup with DEFAULT fallback (← 1200-GET-INTEREST-RATE)
        String acctGroupId = currentAccount.getAcctGroupId();
        BigDecimal interestRate = lookupInterestRate(
                acctGroupId != null ? acctGroupId : InterestCalculationRules.DEFAULT_GROUP_ID,
                typeCode,
                catCode);

        // If rate is zero, skip interest computation (← COBOL: IF DIS-INT-RATE NOT = 0)
        if (interestRate.compareTo(BigDecimal.ZERO) == 0) {
            log.debug("Interest rate is zero for account {} type {} cat {} — skipping",
                    acctId, typeCode, catCode);
            computeFees(item);
            return null;
        }

        // Compute monthly interest (← 1300-COMPUTE-INTEREST)
        BigDecimal monthlyInterest = computeInterest(item.getTranCatBal(), interestRate);
        totalInterest = totalInterest.add(monthlyInterest);
        log.debug("Computed interest — account: {}, type: {}, cat: {}, balance: {}, "
                        + "rate: {}, monthly: {}",
                acctId, typeCode, catCode, item.getTranCatBal(), interestRate, monthlyInterest);

        // Compute fees — stub preserved per COBOL source (← 1400-COMPUTE-FEES)
        computeFees(item);

        // Generate system interest transaction (← 1300-B-WRITE-TX)
        return generateInterestTransaction(item, monthlyInterest);
    }

    // ========================================================================
    // Parm Date Accessors (← LINKAGE SECTION PARM-DATE)
    // ========================================================================

    /**
     * Sets the processing date parameter (← LINKAGE SECTION PARM-DATE PIC X(10)).
     *
     * <p>This date is used for generating transaction IDs in the format
     * {@code {parmDate}-{5-digit-suffix}}. Typically set by the job configuration
     * from the JCL EXEC PARM equivalent (e.g., '2022071800' in INTCALC.jcl).</p>
     *
     * @param parmDate the processing date string (format: YYYY-MM-DD or YYYYMMDD)
     */
    public void setParmDate(String parmDate) {
        this.parmDate = parmDate;
    }

    /**
     * Returns the current processing date parameter.
     *
     * @return the parm date string, or {@code null} if not yet set
     */
    public String getParmDate() {
        return parmDate;
    }

    // ========================================================================
    // Interest Rate Lookup (← 1200-GET-INTEREST-RATE + 1200-A-GET-DEFAULT-INT-RATE)
    // ========================================================================

    /**
     * Looks up the interest rate from the disclosure group table with DEFAULT fallback.
     *
     * <p>Implements the critical 2-attempt lookup pattern from CBACT04C.cbl:</p>
     * <ol>
     *   <li>First attempt with account-specific group ID (← 1200-GET-INTEREST-RATE,
     *       lines 415-440)</li>
     *   <li>If not found (COBOL INVALID KEY / FILE STATUS '23'): retry with 'DEFAULT'
     *       group ID (← 1200-A-GET-DEFAULT-INT-RATE, lines 440-460)</li>
     *   <li>If DEFAULT also not found: fatal error → ABEND
     *       (← 9999-ABEND-PROGRAM)</li>
     * </ol>
     *
     * <p>Per AAP §0.4.3 "Domain Rules Pattern" and §0.7.5 "Extraction Safety Matrix",
     * this method performs the two repository reads required by the COBOL program
     * (primary disclosure-group lookup and DEFAULT-group fallback lookup — the I/O
     * boundary that must remain in the batch layer) and delegates the pure
     * selection logic (primary → DEFAULT → ABEND) to
     * {@link InterestCalculationRules#selectInterestRate(Optional, Optional,
     * String, String, Short)}. The ABEND error-message format (including the
     * {@code "COBOL ABEND equivalent"} suffix) and the
     * {@link CardDemoException} throw-site semantics are preserved byte-for-byte
     * within the rules class per AAP Rule R-001 ("no business-logic rewriting
     * — methods are relocated, not rewritten").</p>
     *
     * @param acctGroupId the account's disclosure group ID (← ACCT-GROUP-ID)
     * @param typeCode    the transaction type code (← TRANCAT-TYPE-CD)
     * @param catCode     the transaction category code (← TRANCAT-CD)
     * @return the interest rate from the disclosure group record (DIS-INT-RATE)
     * @throws CardDemoException if neither the account group nor DEFAULT group is found
     */
    private BigDecimal lookupInterestRate(String acctGroupId, String typeCode, Short catCode) {
        // Primary lookup — account-specific group (← 1200-GET-INTEREST-RATE)
        Optional<DisclosureGroup> primaryGroup = disclosureGroupRepository
                .findByGroupIdAndTypeCodeAndCatCode(acctGroupId, typeCode, catCode);

        // DEFAULT-group lookup — eager second read so the rules class receives
        // both Optional<DisclosureGroup> results and can apply the pure
        // selection logic (primary → DEFAULT → ABEND) without performing any
        // I/O itself (← 1200-A-GET-DEFAULT-INT-RATE).
        Optional<DisclosureGroup> defaultGroup = disclosureGroupRepository
                .findByGroupIdAndTypeCodeAndCatCode(
                        InterestCalculationRules.DEFAULT_GROUP_ID, typeCode, catCode);

        // Delegate the selection + fallback + ABEND handling to the domain rules.
        // Throws CardDemoException (← 9999-ABEND-PROGRAM) when both Optionals
        // are empty — error-message formatting is identical to the source
        // processor's pre-refactor throw site.
        return interestCalculationRules.selectInterestRate(
                primaryGroup, defaultGroup, acctGroupId, typeCode, catCode);
    }

    // ========================================================================
    // Interest Computation (← 1300-COMPUTE-INTEREST, line 467)
    // ========================================================================

    /**
     * Computes the monthly interest amount using the exact COBOL formula.
     *
     * <p>COBOL source (CBACT04C.cbl line 467):</p>
     * <pre>
     * COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200
     * </pre>
     *
     * <p>The formula is preserved without algebraic simplification or rearrangement
     * per AAP §0.8.5. Uses {@code RoundingMode.HALF_EVEN} (banker's rounding) to
     * match the COBOL default rounding behavior. The result scale is 2, matching
     * the COBOL field {@code WS-MONTHLY-INT PIC S9(9)V99 COMP-3}.</p>
     *
     * <p>Per AAP §0.4.3 "Domain Rules Pattern" and §0.7.5 "Extraction Safety Matrix",
     * this method delegates to
     * {@link InterestCalculationRules#computeMonthlyInterest(BigDecimal, BigDecimal)}
     * — the authoritative implementation of the formula. The delegating wrapper
     * is preserved (rather than inlining the call at the single call site in
     * {@link #process(TransactionCategoryBalance)}) to maintain the COBOL
     * paragraph-mapped traceability anchor at the original location and to
     * minimize diff churn for future maintainers. Behavior is byte-identical
     * to the pre-refactor implementation per AAP Rule R-001.</p>
     *
     * @param categoryBalance the transaction category balance (TRAN-CAT-BAL)
     * @param interestRate    the disclosure group interest rate (DIS-INT-RATE)
     * @return the monthly interest amount, rounded to scale 2 with HALF_EVEN
     */
    private BigDecimal computeInterest(BigDecimal categoryBalance, BigDecimal interestRate) {
        // EXACT formula: (balance × rate) / 1200 — NO algebraic simplification.
        // Delegated to the domain rules class per AAP §0.4.3.
        return interestCalculationRules.computeMonthlyInterest(categoryBalance, interestRate);
    }

    // ========================================================================
    // Transaction Generation (← 1300-B-WRITE-TX, lines 473-515)
    // ========================================================================

    /**
     * Generates a system interest transaction record from the computed monthly interest.
     *
     * <p>Maps CBACT04C.cbl paragraph 1300-B-WRITE-TX (lines 473-515). Constructs
     * a {@link Transaction} with fixed type code '01', category code 5 (COBOL '05'),
     * source 'System', and a description identifying the account.</p>
     *
     * <p>Transaction ID format: {@code {parmDate}-{5-digit-suffix}}
     * (e.g., "2024-01-15-00001"). The suffix auto-increments for each generated
     * transaction within the step execution.</p>
     *
     * @param item            the source category balance record (for account ID reference)
     * @param monthlyInterest the computed monthly interest amount
     * @return the constructed interest transaction ready for writing
     */
    private Transaction generateInterestTransaction(TransactionCategoryBalance item,
                                                     BigDecimal monthlyInterest) {
        String acctId = item.getId().getAcctId();

        // Generate transaction ID: {parmDate}-{5-digit suffix} (← WS-TRANID-SUFFIX)
        int suffix = tranIdSuffix.incrementAndGet();
        String tranId = parmDate + "-" + String.format("%05d", suffix);

        // Build description: 'Int. for a/c ' + account ID (← COBOL STRING operation)
        String description = "Int. for a/c " + acctId;

        // Determine card number from cross-reference (← 1110-GET-XREF-DATA)
        String cardNum = (currentXref != null) ? currentXref.getXrefCardNum() : "";

        // Construct the interest transaction (← 1300-B-WRITE-TX field assignments)
        Transaction transaction = new Transaction();
        transaction.setTranId(tranId);
        transaction.setTranTypeCd(INTEREST_TRAN_TYPE_CD);
        transaction.setTranCatCd(INTEREST_TRAN_CAT_CD);
        transaction.setTranSource(INTEREST_TRAN_SOURCE);
        transaction.setTranDesc(description);
        transaction.setTranAmt(monthlyInterest);
        transaction.setTranCardNum(cardNum);
        transaction.setTranOrigTs(LocalDateTime.now());
        transaction.setTranProcTs(LocalDateTime.now());

        // Set merchant fields to empty (← COBOL: MOVE 0/SPACES TO TRAN-MERCHANT-*)
        transaction.setTranMerchantId("");
        transaction.setTranMerchantName("");
        transaction.setTranMerchantCity("");
        transaction.setTranMerchantZip("");

        log.debug("Generated interest transaction — id: {}, account: {}, amount: {}",
                tranId, acctId, monthlyInterest);

        return transaction;
    }

    // ========================================================================
    // Account Update (← 1050-UPDATE-ACCOUNT, lines 350-370)
    // ========================================================================

    /**
     * Applies accumulated interest to the current account and resets cycle counters.
     *
     * <p>Maps CBACT04C.cbl paragraph 1050-UPDATE-ACCOUNT (lines 350-370):</p>
     * <pre>
     * ADD WS-TOTAL-INT TO ACCT-CURR-BAL
     * MOVE 0 TO ACCT-CURR-CYC-CREDIT
     * MOVE 0 TO ACCT-CURR-CYC-DEBIT
     * REWRITE FD-ACCTFILE-REC FROM ACCOUNT-RECORD
     * </pre>
     *
     * <p>Called on account break detection (when a new account ID is encountered)
     * and after step completion ({@link #afterStep(StepExecution)}) to flush the
     * last account's accumulated interest.</p>
     *
     * <p>Per AAP §0.4.3 "Domain Rules Pattern" and §0.7.5 "Extraction Safety Matrix",
     * the guard predicate (non-null account + non-zero accumulated interest) is
     * delegated to
     * {@link InterestCalculationRules#shouldApplyInterest(Account, BigDecimal)}, and
     * the in-memory entity mutations (balance increment + cycle-counter reset) are
     * delegated to
     * {@link InterestCalculationRules#applyInterestToAccount(Account, BigDecimal)}.
     * Persistence (the COBOL {@code REWRITE} equivalent, i.e.
     * {@code accountRepository.save(currentAccount)}) remains the processor's
     * responsibility — the rules class is repository-free by design. Logging of
     * the previous/accumulated/new balance triple is preserved at the processor
     * per AAP Rule R-001 (byte-identical observability).</p>
     */
    private void updateAccount() {
        // Guard: only apply interest when an account is established and the
        // accumulated interest is non-zero (← COBOL source guard semantics,
        // preserved via InterestCalculationRules.shouldApplyInterest).
        if (interestCalculationRules.shouldApplyInterest(currentAccount, totalInterest)) {
            BigDecimal previousBalance = currentAccount.getAcctCurrBal();

            // Apply interest to entity in memory (← CBACT04C 1050-UPDATE-ACCOUNT):
            //   ADD WS-TOTAL-INT TO ACCT-CURR-BAL
            //   MOVE 0 TO ACCT-CURR-CYC-CREDIT / ACCT-CURR-CYC-DEBIT
            // The rules class mutates the Account entity in place but does not
            // persist — persistence remains the processor's responsibility below.
            interestCalculationRules.applyInterestToAccount(currentAccount, totalInterest);
            BigDecimal newBalance = currentAccount.getAcctCurrBal();

            // Persist updated account (← REWRITE FD-ACCTFILE-REC FROM ACCOUNT-RECORD).
            // This I/O boundary remains in the processor per AAP §0.7.5
            // ("persistence is the caller's responsibility — rules class is
            // repository-free by design").
            accountRepository.save(currentAccount);

            log.info("Updated account {} — previous balance: {}, interest added: {}, "
                            + "new balance: {}, cycle credit/debit reset to zero",
                    lastAcctNum, previousBalance, totalInterest, newBalance);
        }
    }

    // ========================================================================
    // Fee Computation Stub (← 1400-COMPUTE-FEES, lines 518-520)
    // ========================================================================

    /**
     * Placeholder for fee computation logic — preserved as no-op per COBOL source.
     *
     * <p>CBACT04C.cbl paragraph 1400-COMPUTE-FEES (lines 518-520) contains only an
     * {@code EXIT} statement with a comment "To be implemented". This stub maintains
     * behavioral parity with the original COBOL program.</p>
     *
     * @param item the transaction category balance record (unused in current implementation)
     */
    private void computeFees(TransactionCategoryBalance item) {
        // Fee computation not yet implemented — preserved as no-op per COBOL source
        // (CBACT04C.cbl line 518: "To be implemented")
        log.debug("Fee computation stub invoked for account: {} — no-op per COBOL source",
                item.getId().getAcctId());
    }
}
