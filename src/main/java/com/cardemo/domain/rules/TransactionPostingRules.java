package com.cardemo.domain.rules;

import com.cardemo.model.entity.Account;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.model.entity.DailyTransaction;
import com.cardemo.model.enums.RejectCode;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Stateless, framework-independent domain rules encapsulating the 4-stage
 * transaction posting validation cascade extracted from the Spring Batch
 * processor per AAP Section 0.4.1 "Domain Rules Pattern".
 *
 * <h2>COBOL Source Reference</h2>
 * <p>Extracted from paragraph {@code 1500-VALIDATE-TRAN} / {@code 1500-VALIDATE-CARD}
 * of {@code CBTRN02C.cbl} (Daily Transaction Posting Program). The original Java
 * translation resides in {@code TransactionPostingProcessor} lines 177-238.
 * All COBOL paragraph references and reject code mappings are preserved verbatim
 * per AAP Rule R-004 (COBOL Traceability Preservation).</p>
 *
 * <h2>The 4-Stage Validation Cascade</h2>
 * <p>Each transaction posting attempt is validated in strict sequential order.
 * The first failing stage short-circuits the cascade and stamps the corresponding
 * COBOL reject code onto the transaction:</p>
 * <ol>
 *   <li><b>Stage 1 — XREF Lookup</b> (paragraph {@code 1500-A-LOOKUP-XREF}) —
 *       Resolves card number to account ID via cross-reference dataset.
 *       Fails with {@link RejectCode#XREF_NOT_FOUND} (code 100) when the
 *       {@link CardCrossReference} lookup returns empty.</li>
 *   <li><b>Stage 2 — Account Lookup</b> (paragraph {@code 1500-B-LOOKUP-ACCT}) —
 *       Reads the {@link Account} record using the account ID from the cross-reference.
 *       Fails with {@link RejectCode#ACCOUNT_NOT_FOUND} (code 101) when the account
 *       lookup returns empty.</li>
 *   <li><b>Stage 3 — Credit Limit Check</b> — Verifies that projected cycle balance
 *       (computed as {@code ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT})
 *       does not exceed {@code ACCT-CREDIT-LIMIT}. <b>Delegates to
 *       {@link CreditLimitRules#isWithinBatchCreditLimit(Account, BigDecimal)}</b>
 *       to avoid duplicating the batch-variant formula. Fails with
 *       {@link RejectCode#CREDIT_LIMIT_EXCEEDED} (code 102) on overlimit.</li>
 *   <li><b>Stage 4 — Expiry Check</b> — Confirms that the account expiration date
 *       is not before the transaction origination date. Fails with
 *       {@link RejectCode#CARD_EXPIRED} (code 103) for expired cards.</li>
 * </ol>
 *
 * <h2>Design Notes</h2>
 * <ul>
 *   <li><b>Stateless:</b> Holds no mutable instance fields. Multiple batch threads
 *       may call methods concurrently without synchronization.</li>
 *   <li><b>Framework-independent:</b> The only Spring annotation permitted in this
 *       class is {@link Component} (per AAP §0.4.1 folder spec). No Spring Batch,
 *       Spring Data, or Spring Transaction imports appear — keeping business rules
 *       isolated from infrastructure concerns.</li>
 *   <li><b>No repository injection:</b> Callers (typically
 *       {@code TransactionPostingProcessor}) resolve entities via repositories and
 *       pass pre-resolved {@link Optional} instances to the stage methods. This
 *       preserves the separation between business rules (this class) and data
 *       access (the processor).</li>
 *   <li><b>Mutable step-state NOT relocated:</b> Per AAP §0.7.1 the
 *       {@code goodTranCount}, {@code badTranCount}, and {@code rejections} list
 *       remain in {@code TransactionPostingProcessor} as Spring Batch step-execution
 *       state. Only the validation decision logic is extracted here.</li>
 *   <li><b>BigDecimal semantics (AAP §0.8.2):</b> All monetary comparisons use
 *       {@link BigDecimal#compareTo(BigDecimal)}, never {@code equals()} or {@code ==},
 *       to preserve COBOL PIC S9(nn)V99 COMP-3 (packed decimal) semantics where
 *       numerical equality must ignore scale.</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <p>Consumers may either invoke individual stage methods for fine-grained control
 * (matching the inline cascade structure of the original processor), or invoke the
 * convenience method
 * {@link #validateCascade(DailyTransaction, Optional, Optional) validateCascade}
 * which executes all four stages in COBOL order and returns a structured
 * {@link ValidationResult}.</p>
 *
 * @see com.cardemo.batch.processors.TransactionPostingProcessor
 * @see CreditLimitRules
 * @see RejectCode
 */
@Component
public final class TransactionPostingRules {

    /**
     * Batch-variant credit-limit rule holder, invoked by {@link #validateCreditLimit(Account, BigDecimal)}
     * to perform Stage 3 of the cascade. Injected via constructor to keep this
     * class testable without a Spring context and to reflect the "compose rules"
     * design chosen by the Domain Rules Pattern (AAP §0.4.1).
     */
    private final CreditLimitRules creditLimitRules;

    /**
     * Constructs the rules class with its collaborating credit-limit rules bean.
     * Spring resolves {@link CreditLimitRules} as a peer {@link Component} in the
     * same {@code com.cardemo.domain.rules} package.
     *
     * @param creditLimitRules the same-package credit-limit rules bean that
     *                         implements the batch-variant credit-limit formula
     *                         mapped to COBOL paragraph
     *                         {@code 1500-VALIDATE-CRED-LIMIT}; must not be
     *                         {@code null}
     */
    public TransactionPostingRules(CreditLimitRules creditLimitRules) {
        this.creditLimitRules = creditLimitRules;
    }

    // -----------------------------------------------------------------
    // Stage 1 — XREF Lookup
    // -----------------------------------------------------------------

    /**
     * Stage 1 of the cascade: checks whether the card-to-account cross-reference
     * lookup succeeded.
     *
     * <p>The following COBOL comment block is preserved verbatim from the source
     * processor (lines 183-186) per AAP Rule R-004:</p>
     * <pre>
     * ---------------------------------------------------------------
     * Stage 1 — XREF Lookup (1500-A-LOOKUP-XREF, lines 380-392)
     * Resolve card number to account ID via cross-reference dataset.
     * COBOL: READ XREF-FILE INTO CARD-XREF-RECORD; INVALID KEY → code 100
     * ---------------------------------------------------------------
     * </pre>
     *
     * <p>The rules class accepts a pre-resolved {@link Optional} from the caller
     * rather than performing repository access itself, keeping this class
     * framework-independent (no {@code com.cardemo.repository} imports).</p>
     *
     * @param xrefOpt the pre-resolved cross-reference lookup result produced by
     *                the caller (typically
     *                {@code CardCrossReferenceRepository.findById(dalytranCardNum)})
     * @return {@link RejectCode#XREF_NOT_FOUND} if the cross-reference is absent
     *         (Stage 1 failure — COBOL code 100); {@code null} if the cross-reference
     *         was resolved successfully (Stage 1 passed)
     */
    public RejectCode validateCrossReference(Optional<CardCrossReference> xrefOpt) {
        if (xrefOpt.isEmpty()) {
            return RejectCode.XREF_NOT_FOUND;
        }
        return null;
    }

    // -----------------------------------------------------------------
    // Stage 2 — Account Lookup
    // -----------------------------------------------------------------

    /**
     * Stage 2 of the cascade: checks whether the account lookup succeeded.
     *
     * <p>The following COBOL comment block is preserved verbatim from the source
     * processor (lines 197-200) per AAP Rule R-004:</p>
     * <pre>
     * ---------------------------------------------------------------
     * Stage 2 — Account Lookup (1500-B-LOOKUP-ACCT, lines 393-402)
     * Read account record using account ID from cross-reference.
     * COBOL: READ ACCOUNT-FILE INTO ACCOUNT-RECORD; INVALID KEY → code 101
     * ---------------------------------------------------------------
     * </pre>
     *
     * <p>Callers resolve the account via
     * {@code AccountRepository.findById(xref.getXrefAcctId())} before invoking
     * this method.</p>
     *
     * @param acctOpt the pre-resolved account lookup result produced by the caller
     * @return {@link RejectCode#ACCOUNT_NOT_FOUND} if the account is absent
     *         (Stage 2 failure — COBOL code 101); {@code null} if the account
     *         was resolved successfully (Stage 2 passed)
     */
    public RejectCode validateAccount(Optional<Account> acctOpt) {
        if (acctOpt.isEmpty()) {
            return RejectCode.ACCOUNT_NOT_FOUND;
        }
        return null;
    }

    // -----------------------------------------------------------------
    // Stage 3 — Credit Limit Check
    // -----------------------------------------------------------------

    /**
     * Stage 3 of the cascade: checks whether the transaction keeps the account
     * within its credit limit.
     *
     * <p>The following COBOL comment block is preserved verbatim from the source
     * processor (lines 211-216) per AAP Rule R-004:</p>
     * <pre>
     * ---------------------------------------------------------------
     * Stage 3 — Credit Limit Check (lines 403-413)
     * COBOL: COMPUTE WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT
     *            - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT
     *        IF ACCT-CREDIT-LIMIT &lt; WS-TEMP-BAL → code 102
     * CRITICAL: All computations use BigDecimal (WS-TEMP-BAL is
     *           PIC S9(09)V99 COMP-3). Use compareTo(), never equals().
     * ---------------------------------------------------------------
     * </pre>
     *
     * <p>This method delegates the arithmetic to
     * {@link CreditLimitRules#isWithinBatchCreditLimit(Account, BigDecimal)} to
     * avoid duplicating the batch-variant formula. That method is the single
     * source of truth for the batch posting credit-limit rule and is mapped to
     * COBOL paragraph {@code 1500-VALIDATE-CRED-LIMIT}.</p>
     *
     * @param account           the account whose credit cycle balance and credit
     *                          limit drive the check (must not be {@code null});
     *                          accesses {@link Account#getAcctCurrCycCredit()},
     *                          {@link Account#getAcctCurrCycDebit()}, and
     *                          {@link Account#getAcctCreditLimit()} via the
     *                          delegate
     * @param transactionAmount the transaction amount being posted
     *                          ({@code DALYTRAN-AMT}, PIC S9(09)V99)
     * @return {@link RejectCode#CREDIT_LIMIT_EXCEEDED} if the transaction would
     *         push the account over its credit limit (Stage 3 failure — COBOL
     *         code 102); {@code null} if the account remains within its credit
     *         limit (Stage 3 passed)
     */
    public RejectCode validateCreditLimit(Account account, BigDecimal transactionAmount) {
        if (creditLimitRules.isWithinBatchCreditLimit(account, transactionAmount)) {
            return null;
        }
        return RejectCode.CREDIT_LIMIT_EXCEEDED;
    }

    // -----------------------------------------------------------------
    // Stage 4 — Expiry Check
    // -----------------------------------------------------------------

    /**
     * Stage 4 of the cascade: checks whether the account has expired on or
     * before the transaction origination date.
     *
     * <p>The following COBOL comment block is preserved verbatim from the source
     * processor (lines 227-232) per AAP Rule R-004:</p>
     * <pre>
     * ---------------------------------------------------------------
     * Stage 4 — Expiry Check (lines 414-420)
     * COBOL: IF ACCT-EXPIRAION-DATE &lt; DALYTRAN-ORIG-TS(1:10) → code 103
     * Note: "EXPIRAION" is the original COBOL field name (typo preserved
     *       in source). Java entity uses corrected name acctExpDate.
     * Comparison: account expired if expiration date is BEFORE
     *             the transaction origination date.
     * ---------------------------------------------------------------
     * </pre>
     *
     * <p>The COBOL field name {@code ACCT-EXPIRAION-DATE} contains the spelling
     * "EXPIRAION" (missing the second 'T'). This typo is preserved verbatim in
     * the comment block above as documentation of the mainframe source. The
     * Java entity field has been renamed to {@code acctExpDate} with standard
     * English spelling.</p>
     *
     * @param account         the account whose expiration date is compared
     *                        against the transaction date; accesses
     *                        {@link Account#getAcctExpDate()}
     * @param transactionDate the transaction origination date (typically derived
     *                        from {@code DailyTransaction.getDalytranOrigTs().toLocalDate()})
     * @return {@link RejectCode#CARD_EXPIRED} if the account expiration date is
     *         strictly before the transaction date (Stage 4 failure — COBOL
     *         code 103); {@code null} if the account is not yet expired on the
     *         transaction date (Stage 4 passed)
     */
    public RejectCode validateExpiry(Account account, LocalDate transactionDate) {
        if (account.getAcctExpDate().isBefore(transactionDate)) {
            return RejectCode.CARD_EXPIRED;
        }
        return null;
    }

    // -----------------------------------------------------------------
    // Full cascade — orchestrates Stage 1 → 2 → 3 → 4
    // -----------------------------------------------------------------

    /**
     * Executes the full 4-stage validation cascade in COBOL order with
     * short-circuit evaluation on the first failing stage, returning a structured
     * {@link ValidationResult} capturing either the resolved account (on approval)
     * or the specific stage's reject code (on failure).
     *
     * <p>Maps to COBOL paragraph {@code 1500-VALIDATE-TRAN} /
     * {@code 1500-VALIDATE-CARD} of {@code CBTRN02C.cbl}. The evaluation order
     * preserves the sequential PERFORM structure of the original COBOL program:</p>
     * <ol>
     *   <li>{@link #validateCrossReference(Optional)} — Stage 1 — {@link RejectCode#XREF_NOT_FOUND} (100)</li>
     *   <li>{@link #validateAccount(Optional)} — Stage 2 — {@link RejectCode#ACCOUNT_NOT_FOUND} (101)</li>
     *   <li>{@link #validateCreditLimit(Account, BigDecimal)} — Stage 3 —
     *       {@link RejectCode#CREDIT_LIMIT_EXCEEDED} (102)</li>
     *   <li>{@link #validateExpiry(Account, LocalDate)} — Stage 4 —
     *       {@link RejectCode#CARD_EXPIRED} (103)</li>
     * </ol>
     *
     * <p>The {@code DailyTransaction.getDalytranOrigTs()} getter returns a
     * {@link java.time.LocalDateTime}; the cascade converts it to a
     * {@link LocalDate} via {@code .toLocalDate()} (matching source processor
     * line 234) for comparison against {@link Account#getAcctExpDate()}.</p>
     *
     * <p>Behavioral parity guarantee (AAP Rule R-001): for any tuple of inputs
     * accepted by the original processor's inline cascade, this method produces
     * identical accept/reject outcomes.</p>
     *
     * @param item     the daily transaction being validated; accesses
     *                 {@link DailyTransaction#getDalytranAmt()} for Stage 3 and
     *                 {@link DailyTransaction#getDalytranOrigTs()} for Stage 4
     *                 (must not be {@code null})
     * @param xrefOpt  the pre-resolved cross-reference lookup result (Stage 1)
     * @param acctOpt  the pre-resolved account lookup result (Stage 2)
     * @return a {@link ValidationResult} — {@link ValidationResult#approved(Account)}
     *         with the resolved account if all four stages pass;
     *         {@link ValidationResult#rejected(RejectCode)} with the first failing
     *         stage's reject code if any stage fails
     */
    public ValidationResult validateCascade(
            DailyTransaction item,
            Optional<CardCrossReference> xrefOpt,
            Optional<Account> acctOpt) {

        // Stage 1 — XREF Lookup (1500-A-LOOKUP-XREF)
        RejectCode stage1 = validateCrossReference(xrefOpt);
        if (stage1 != null) {
            return ValidationResult.rejected(stage1);
        }

        // Stage 2 — Account Lookup (1500-B-LOOKUP-ACCT)
        RejectCode stage2 = validateAccount(acctOpt);
        if (stage2 != null) {
            return ValidationResult.rejected(stage2);
        }

        // Acct Optional is guaranteed present at this point — Stage 2 passed
        Account account = acctOpt.get();

        // Stage 3 — Credit Limit Check (delegates to CreditLimitRules)
        RejectCode stage3 = validateCreditLimit(account, item.getDalytranAmt());
        if (stage3 != null) {
            return ValidationResult.rejected(stage3);
        }

        // Stage 4 — Expiry Check
        // COBOL DALYTRAN-ORIG-TS(1:10) captures the date portion of the 26-byte
        // timestamp; in Java this maps to LocalDateTime.toLocalDate() (source
        // processor line 234).
        LocalDate tranDate = item.getDalytranOrigTs().toLocalDate();
        RejectCode stage4 = validateExpiry(account, tranDate);
        if (stage4 != null) {
            return ValidationResult.rejected(stage4);
        }

        // All 4 stages passed — approve with resolved account
        return ValidationResult.approved(account);
    }

    // -----------------------------------------------------------------
    // Cycle classification helper
    // -----------------------------------------------------------------

    /**
     * Classifies a transaction amount as a cycle credit or cycle debit per the
     * COBOL posting rule.
     *
     * <p>The following COBOL comment block is preserved verbatim from the source
     * processor (lines 367-371) per AAP Rule R-004:</p>
     * <pre>
     * Step 2: Classify to cycle credit or debit
     * COBOL: IF DALYTRAN-AMT &gt;= 0 → ADD TO ACCT-CURR-CYC-CREDIT
     *        ELSE → ADD TO ACCT-CURR-CYC-DEBIT
     * Note: &gt;= 0 means zero amounts go to cycle credit (COBOL behavior)
     * </pre>
     *
     * <p>The {@code >=} operator (as opposed to {@code >}) is the authoritative
     * COBOL semantic: zero-valued transactions accumulate into
     * {@code ACCT-CURR-CYC-CREDIT}, not {@code ACCT-CURR-CYC-DEBIT}. This method
     * is used by the processor's {@code updateAccount()} routine to drive the
     * cycle-bucket selection after all four validation stages pass.</p>
     *
     * <p>Uses {@link BigDecimal#compareTo(BigDecimal)} with {@link BigDecimal#ZERO}
     * to honor scale-ignoring numerical equality (AAP §0.8.2); for example,
     * {@code 0.00} and {@code 0} both classify as cycle credit.</p>
     *
     * @param amount the transaction amount ({@code DALYTRAN-AMT})
     * @return {@code true} if the amount is &gt;= 0 (route to
     *         {@code ACCT-CURR-CYC-CREDIT}); {@code false} if the amount is
     *         &lt; 0 (route to {@code ACCT-CURR-CYC-DEBIT})
     */
    public boolean isCreditCycleAmount(BigDecimal amount) {
        return amount.compareTo(BigDecimal.ZERO) >= 0;
    }

    // -----------------------------------------------------------------
    // Nested record — ValidationResult
    // -----------------------------------------------------------------

    /**
     * Immutable value object capturing the outcome of
     * {@link TransactionPostingRules#validateCascade(DailyTransaction, Optional, Optional)}.
     *
     * <p>Two mutually exclusive states are represented:</p>
     * <ul>
     *   <li><b>Approved</b>: {@code approved == true}, {@code account} is the
     *       resolved {@link Account} that passed all four stages, and
     *       {@code rejectCode} is {@code null}. Produced by the
     *       {@link #approved(Account)} static factory.</li>
     *   <li><b>Rejected</b>: {@code approved == false}, {@code account} is
     *       {@code null}, and {@code rejectCode} is the {@link RejectCode}
     *       identifying which stage failed (100, 101, 102, or 103). Produced
     *       by the {@link #rejected(RejectCode)} static factory.</li>
     * </ul>
     *
     * <p>Consumers should always use the static factories to build instances —
     * they preserve the invariant that {@code account} and {@code rejectCode}
     * are never simultaneously non-null, nor simultaneously null.</p>
     *
     * @param approved   whether all four validation stages passed
     * @param account    the resolved {@link Account} on approval; {@code null}
     *                   on rejection
     * @param rejectCode the COBOL reject code identifying the first failing
     *                   stage; {@code null} on approval
     */
    public record ValidationResult(boolean approved, Account account, RejectCode rejectCode) {

        /**
         * Constructs an approval result indicating that all four validation
         * stages passed.
         *
         * @param account the resolved account from Stage 2; must not be
         *                {@code null}
         * @return a {@code ValidationResult} with
         *         {@code approved == true}, {@code account == account}, and
         *         {@code rejectCode == null}
         */
        public static ValidationResult approved(Account account) {
            return new ValidationResult(true, account, null);
        }

        /**
         * Constructs a rejection result indicating that one stage failed.
         *
         * @param rejectCode the {@link RejectCode} corresponding to the first
         *                   failing stage (100, 101, 102, or 103); must not
         *                   be {@code null}
         * @return a {@code ValidationResult} with
         *         {@code approved == false}, {@code account == null}, and
         *         {@code rejectCode == rejectCode}
         */
        public static ValidationResult rejected(RejectCode rejectCode) {
            return new ValidationResult(false, null, rejectCode);
        }
    }
}
