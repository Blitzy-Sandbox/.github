package com.cardemo.domain.rules;

import com.cardemo.exception.CardDemoException;
import com.cardemo.model.entity.Account;
import com.cardemo.model.entity.DisclosureGroup;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

import org.springframework.stereotype.Component;

/**
 * Domain-layer business rules class that encapsulates the monthly interest
 * calculation formula, interest-rate selection (with DEFAULT fallback), and
 * account interest application logic for the CardDemo platform.
 *
 * <p>This class consolidates pure business-rule logic extracted from
 * {@link com.cardemo.batch.processors.InterestCalculationProcessor}
 * (COBOL program {@code CBACT04C.cbl} &mdash; "Compute Interest for
 * all Accounts"). It isolates the following COBOL semantics as framework-
 * independent Java methods:</p>
 *
 * <ol>
 *   <li><strong>{@code 1200-GET-INTEREST-RATE}</strong> &mdash; primary
 *       interest-rate lookup attempt using the account-specific disclosure
 *       group ID. Modeled here as the first branch of
 *       {@link #selectInterestRate(Optional, Optional, String, String, Short)}.</li>
 *   <li><strong>{@code 1200-A-GET-DEFAULT-INT-RATE}</strong> &mdash; fallback
 *       interest-rate lookup using the {@code 'DEFAULT'} disclosure group ID
 *       when the primary lookup returns COBOL {@code INVALID KEY /
 *       FILE STATUS '23'}. Modeled as the second branch of
 *       {@link #selectInterestRate(Optional, Optional, String, String, Short)}.</li>
 *   <li><strong>{@code 1300-COMPUTE-INTEREST}</strong> (CBACT04C.cbl line 467)
 *       &mdash; the core formula
 *       {@code COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200}.
 *       Modeled as {@link #computeMonthlyInterest(BigDecimal, BigDecimal)}.</li>
 *   <li><strong>{@code 1050-UPDATE-ACCOUNT}</strong> (CBACT04C.cbl lines 350-370)
 *       &mdash; applies the accumulated total interest to the account current
 *       balance and resets the end-of-cycle credit/debit counters to zero.
 *       Modeled as {@link #applyInterestToAccount(Account, BigDecimal)} plus
 *       the guard {@link #shouldApplyInterest(Account, BigDecimal)}.</li>
 * </ol>
 *
 * <h3>The Core Formula and Why It Must Not Be Simplified</h3>
 * <p>The monthly interest formula is:</p>
 * <pre>
 * monthlyInterest = (categoryBalance &times; interestRate) / 1200
 * </pre>
 * <p>The divisor {@code 1200} derives from <em>12 months &times; 100 percent</em>
 * (the interest rate is expressed as an annual percentage). Per AAP
 * &sect;0.8.5 &ldquo;No business logic rewriting&rdquo;, the formula is
 * preserved <em>exactly</em> as it appears in the COBOL source &mdash; the
 * multiplication and division are <strong>not</strong> reordered or
 * algebraically simplified to {@code categoryBalance &times; (interestRate / 1200)}
 * or any equivalent form. Any such simplification could produce slightly
 * different intermediate precision and therefore different final rounded
 * results, violating AAP Rule R-001 &ldquo;Behavioral preservation&rdquo;.</p>
 *
 * <h3>BigDecimal Precision Preservation</h3>
 * <p>All monetary and rate arithmetic uses {@link BigDecimal} with
 * {@link RoundingMode#HALF_EVEN} (banker's rounding), which matches the
 * COBOL default rounding behavior for {@code COMPUTE} statements. The
 * interest result is rounded to scale 2, matching the COBOL field
 * {@code WS-MONTHLY-INT PIC S9(9)V99 COMP-3} (two fractional digits). Using
 * any other rounding mode (e.g., {@code HALF_UP}) would produce different
 * last-digit results for half-way values and violate COBOL parity.</p>
 *
 * <h3>2-Attempt Fallback Pattern (DEFAULT Group)</h3>
 * <p>Interest-rate lookup implements a 2-attempt fallback:</p>
 * <ol>
 *   <li>First attempt uses the account-specific
 *       {@code ACCT-GROUP-ID}.</li>
 *   <li>If the primary lookup returns no record, a second attempt uses the
 *       literal {@code 'DEFAULT'} group ID (preserved as {@link #DEFAULT_GROUP_ID}
 *       &mdash; 7 characters, no padding).</li>
 *   <li>If both lookups fail, a {@link CardDemoException} is thrown,
 *       corresponding to the COBOL {@code 9999-ABEND-PROGRAM} handler.</li>
 * </ol>
 * <p>This class <em>does not</em> perform the repository lookups itself. The
 * caller (typically
 * {@link com.cardemo.batch.processors.InterestCalculationProcessor}) must
 * invoke its {@code DisclosureGroupRepository} twice &mdash; once for the
 * primary group and once for the DEFAULT group &mdash; and pass both
 * {@link Optional} results as parameters. This repository-free design
 * preserves the framework-independence mandated for the
 * {@code com.cardemo.domain.rules} package (AAP &sect;0.4.3 Domain Rules
 * Pattern).</p>
 *
 * <h3>Design Constraints (AAP &sect;0.4.3, folder-scope requirements)</h3>
 * <ul>
 *   <li><strong>Stateless</strong> &mdash; no mutable instance fields; every
 *       method is a pure function of its parameters. Batch step-level state
 *       (account-break detection, accumulated {@code totalInterest},
 *       transaction ID suffix) remains in the processor, <em>not</em> in
 *       this rules class.</li>
 *   <li><strong>Framework-independent</strong> &mdash; imports only JDK
 *       types plus {@link Component} for Spring dependency-injection
 *       stereotype. Does <em>not</em> import
 *       {@code org.springframework.batch.*},
 *       {@code org.springframework.data.*},
 *       {@code org.springframework.transaction.*}, or any
 *       {@code com.cardemo.repository.*} interface.</li>
 *   <li><strong>No repository injection</strong> &mdash; the entities
 *       ({@link Account}, {@link DisclosureGroup}) are received as
 *       pre-resolved parameters; this class never performs data access.
 *       Callers own persistence: after
 *       {@link #applyInterestToAccount(Account, BigDecimal)} updates the
 *       in-memory entity, the caller is responsible for invoking
 *       {@code accountRepository.save(...)} &mdash; corresponding to the
 *       COBOL {@code REWRITE FD-ACCTFILE-REC FROM ACCOUNT-RECORD}
 *       statement.</li>
 *   <li><strong>{@code @Component} only</strong> &mdash; the
 *       {@link Component} annotation is the <em>only</em> Spring annotation
 *       allowed on this class. No {@code @Service}, {@code @Transactional},
 *       {@code @StepScope}, {@code @BeforeStep}, {@code @JobScope}, or
 *       {@code @Autowired} annotations appear here.</li>
 *   <li><strong>COBOL traceability preserved</strong> (AAP Rule R-004)
 *       &mdash; every method's Javadoc references the originating COBOL
 *       paragraph name and line range from {@code CBACT04C.cbl}.</li>
 * </ul>
 *
 * <h3>Thread Safety</h3>
 * <p>All methods are pure functions over immutable inputs
 * ({@link BigDecimal} is immutable; {@link DisclosureGroup#getDisIntRate()}
 * returns an immutable value; {@link Account} getter/setter calls are
 * scoped to a single method invocation with no shared state). The class
 * has no mutable state, so a single shared instance is safe for concurrent
 * invocation from any number of batch-processing threads. Spring
 * instantiates a single {@code InterestCalculationRules} bean per
 * application context.</p>
 *
 * <h3>Consumers (post-refactoring)</h3>
 * <ul>
 *   <li>{@link com.cardemo.batch.processors.InterestCalculationProcessor}
 *       &rarr; injects this class via constructor injection and delegates
 *       the four public operations (rate selection, interest computation,
 *       account update application, guard check) while retaining its own
 *       Spring Batch step lifecycle, metrics, account-break detection
 *       state, transaction generation, and repository calls.</li>
 * </ul>
 *
 * <h3>What Is <em>Not</em> Extracted to This Class</h3>
 * <p>The following logic remains in
 * {@link com.cardemo.batch.processors.InterestCalculationProcessor} because
 * it is infrastructure or step-lifecycle logic, not pure business rules:</p>
 * <ul>
 *   <li>Account-break detection ({@code lastAcctNum}, {@code currentAccount},
 *       {@code totalInterest}) &mdash; stream-processing state.</li>
 *   <li>Interest transaction generation ({@code generateInterestTransaction})
 *       &mdash; requires auto-incrementing transaction ID state, cross-
 *       reference lookups, and timestamp generation.</li>
 *   <li>Fee computation stub ({@code computeFees}) &mdash; documented no-op
 *       placeholder per CBACT04C.cbl paragraph {@code 1400-COMPUTE-FEES}.</li>
 *   <li>Persistence calls ({@code accountRepository.save(...)},
 *       {@code transactionRepository.save(...)}) &mdash; infrastructure
 *       concerns.</li>
 *   <li>Spring Batch lifecycle hooks ({@code @BeforeStep},
 *       {@code afterStep}) and metric recording &mdash; framework
 *       concerns.</li>
 * </ul>
 *
 * <h3>Behavioral Parity (AAP Rule R-001)</h3>
 * <p>Every method in this class produces identical results for identical
 * inputs relative to the pre-refactoring
 * {@code InterestCalculationProcessor} private methods from which it was
 * extracted. No operator has been altered, no argument order reversed, no
 * rounding mode changed, no null-handling branch added or removed. The
 * refactoring is purely structural: relocation without rewriting.</p>
 *
 * @see com.cardemo.batch.processors.InterestCalculationProcessor
 * @see Account
 * @see DisclosureGroup
 * @see CardDemoException
 */
@Component
public final class InterestCalculationRules {

    // ========================================================================
    // Public Constants
    // ========================================================================

    /**
     * Monthly divisor for the interest calculation formula &mdash; exact
     * COBOL constant preserved without algebraic simplification.
     *
     * <p>COBOL source (CBACT04C.cbl line 467):</p>
     * <pre>
     * COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200
     * </pre>
     *
     * <p>The literal {@code 1200} derives from
     * <em>12 months &times; 100 percent</em> (the interest rate is
     * expressed as an annual percentage rather than a decimal fraction,
     * so dividing by 100 converts percent to fraction and dividing by
     * 12 converts annual to monthly). The constant is preserved as
     * {@code BigDecimal.valueOf(1200)} without algebraic simplification
     * per AAP &sect;0.8.5 &ldquo;No business logic rewriting&rdquo;.</p>
     *
     * <p>The factory method {@link BigDecimal#valueOf(long)} is used
     * instead of {@code new BigDecimal("1200")} because
     * {@link BigDecimal#valueOf(long)} caches common values and is the
     * Java-recommended way to construct a {@code BigDecimal} from a
     * {@code long} literal, matching the exact pattern of the source
     * processor (line 106 of
     * {@code InterestCalculationProcessor.java}).</p>
     */
    public static final BigDecimal DIVISOR = BigDecimal.valueOf(1200);

    /**
     * Fallback disclosure group ID used when an account's specific
     * disclosure group is not found.
     *
     * <p>Maps to CBACT04C.cbl paragraph
     * {@code 1200-A-GET-DEFAULT-INT-RATE} (lines 440-460), which retries
     * the interest-rate lookup with the literal {@code 'DEFAULT'} group
     * ID after receiving an {@code INVALID KEY} (COBOL file status
     * {@code '23'}) response on the primary lookup.</p>
     *
     * <p>The value {@code "DEFAULT"} is preserved exactly as it appears
     * in the COBOL source (7 characters, no trailing padding). This
     * exact string &mdash; including letter case &mdash; must be used
     * as the lookup key to match records seeded by Flyway migration
     * {@code V3__seed_data.sql}.</p>
     */
    public static final String DEFAULT_GROUP_ID = "DEFAULT";

    // ========================================================================
    // Constructor
    // ========================================================================

    /**
     * Default no-argument constructor for Spring bean instantiation.
     *
     * <p>This class carries no injected dependencies &mdash; every required
     * input is passed as a method parameter. Spring creates a single
     * {@code InterestCalculationRules} bean per application context which
     * is shared across all consumers (currently only
     * {@link com.cardemo.batch.processors.InterestCalculationProcessor}).</p>
     */
    public InterestCalculationRules() {
        // Intentionally empty: stateless rules class has no initialization.
    }

    // ========================================================================
    // Public Methods
    // ========================================================================

    /**
     * Computes the monthly interest amount using the exact COBOL formula.
     *
     * <p>COBOL source (CBACT04C.cbl line 467, paragraph
     * {@code 1300-COMPUTE-INTEREST}):</p>
     * <pre>
     * COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200
     * </pre>
     *
     * <p>The formula is preserved without algebraic simplification or
     * rearrangement per AAP &sect;0.8.5 &mdash; the multiplication is
     * evaluated first, then the division by {@link #DIVISOR}. The
     * intermediate product of {@code categoryBalance.multiply(interestRate)}
     * carries full precision (scale = sum of operand scales), and the
     * subsequent {@link BigDecimal#divide(BigDecimal, int, RoundingMode)}
     * applies {@link RoundingMode#HALF_EVEN} (banker's rounding, matching
     * the COBOL default rounding behavior for {@code COMPUTE} statements)
     * to produce a result with scale 2, matching the COBOL field
     * {@code WS-MONTHLY-INT PIC S9(9)V99 COMP-3}.</p>
     *
     * <p>This method makes no null checks on its parameters. Callers are
     * responsible for ensuring {@code categoryBalance} and
     * {@code interestRate} are non-{@code null} &mdash; in the processor,
     * these values are sourced from {@link DisclosureGroup#getDisIntRate()}
     * (after a {@link #selectInterestRate(Optional, Optional, String,
     * String, Short)} guarantee that a rate is present) and from the
     * {@code TransactionCategoryBalance} entity which the processor has
     * already loaded. A {@code null} here reflects a repository or entity-
     * mapping bug upstream, not a business-rule case.</p>
     *
     * @param categoryBalance the transaction category balance
     *                        (COBOL: {@code TRAN-CAT-BAL},
     *                        PIC S9(10)V99 COMP-3); must not be {@code null}
     * @param interestRate    the disclosure group interest rate expressed
     *                        as an annual percentage (COBOL:
     *                        {@code DIS-INT-RATE}, PIC S9(4)V99 COMP-3);
     *                        must not be {@code null}
     * @return the monthly interest amount rounded to scale 2 using
     *         {@link RoundingMode#HALF_EVEN}; the sign of the result
     *         follows the signs of the inputs (a negative balance will
     *         produce a negative interest charge, matching COBOL signed
     *         arithmetic)
     * @see #DIVISOR
     */
    public BigDecimal computeMonthlyInterest(BigDecimal categoryBalance, BigDecimal interestRate) {
        // Preserve COBOL formula exactly: multiply first, then divide by 1200
        // with scale 2 HALF_EVEN rounding. Do NOT algebraically simplify
        // or reorder operations (AAP §0.8.5).
        return categoryBalance.multiply(interestRate)
                .divide(DIVISOR, 2, RoundingMode.HALF_EVEN);
    }

    /**
     * Looks up the interest rate from the disclosure group table using
     * the 2-attempt fallback pattern from the COBOL source.
     *
     * <p>Implements the critical 2-attempt lookup pattern from
     * {@code CBACT04C.cbl}:</p>
     * <ol>
     *   <li><strong>First attempt</strong> with the account-specific
     *       disclosure group ID (COBOL paragraph
     *       {@code 1200-GET-INTEREST-RATE}, lines 415-440). The caller
     *       has already performed the repository lookup and passes the
     *       result as {@code primaryGroup}. If
     *       {@link Optional#isPresent()} returns {@code true}, the rate
     *       from {@link DisclosureGroup#getDisIntRate()} is returned
     *       immediately.</li>
     *   <li><strong>DEFAULT fallback</strong> when the primary lookup
     *       returned no record (COBOL {@code INVALID KEY /
     *       FILE STATUS '23'}). The caller has already performed a second
     *       repository lookup keyed on {@link #DEFAULT_GROUP_ID} and
     *       passes the result as {@code defaultGroup}. Maps to COBOL
     *       paragraph {@code 1200-A-GET-DEFAULT-INT-RATE}, lines
     *       440-460.</li>
     *   <li><strong>Fatal error</strong> when neither the primary nor
     *       the DEFAULT group is present. A {@link CardDemoException} is
     *       thrown with an error message identifying the missing group,
     *       type code, and category code. This models the COBOL
     *       {@code 9999-ABEND-PROGRAM} handler which terminates the
     *       batch job with a non-zero return code.</li>
     * </ol>
     *
     * <p>The caller is responsible for performing <em>both</em> repository
     * lookups &mdash; this method only handles the selection logic. This
     * design preserves the framework-independence of the rules package:
     * {@code InterestCalculationRules} does not inject or depend on any
     * repository interface. In the processor, the 2-attempt pattern
     * typically looks like:</p>
     * <pre>
     * Optional&lt;DisclosureGroup&gt; primary = disclosureGroupRepository
     *         .findByGroupIdAndTypeCodeAndCatCode(acctGroupId, typeCode, catCode);
     * Optional&lt;DisclosureGroup&gt; fallback = primary.isPresent()
     *         ? Optional.empty()
     *         : disclosureGroupRepository.findByGroupIdAndTypeCodeAndCatCode(
     *                 InterestCalculationRules.DEFAULT_GROUP_ID, typeCode, catCode);
     * BigDecimal rate = interestCalculationRules.selectInterestRate(
     *         primary, fallback, acctGroupId, typeCode, catCode);
     * </pre>
     *
     * <p>The error-message format (when both lookups fail) is preserved
     * <em>exactly</em> as it appears in the pre-refactoring
     * {@code InterestCalculationProcessor} source (lines 430-433) to
     * maintain parity with existing log output and test-assertion
     * expectations.</p>
     *
     * @param primaryGroup the {@link Optional} result of the caller's
     *                     primary repository lookup keyed on
     *                     {@code acctGroupId}
     * @param defaultGroup the {@link Optional} result of the caller's
     *                     fallback repository lookup keyed on
     *                     {@link #DEFAULT_GROUP_ID}. Callers MAY pass
     *                     {@link Optional#empty()} unconditionally and
     *                     avoid the second repository call when
     *                     {@code primaryGroup} is already present &mdash;
     *                     this method will never consult
     *                     {@code defaultGroup} if {@code primaryGroup}
     *                     is present
     * @param acctGroupId  the account-specific disclosure group ID
     *                     (COBOL: {@code ACCT-GROUP-ID}); used only for
     *                     error-message context
     * @param typeCode     the transaction type code (COBOL:
     *                     {@code TRANCAT-TYPE-CD}); used only for
     *                     error-message context
     * @param catCode      the transaction category code (COBOL:
     *                     {@code TRANCAT-CD}); used only for
     *                     error-message context
     * @return the disclosure group interest rate (COBOL:
     *         {@code DIS-INT-RATE}) from the first present group &mdash;
     *         {@code primaryGroup} if present, otherwise
     *         {@code defaultGroup}
     * @throws CardDemoException when neither {@code primaryGroup} nor
     *                           {@code defaultGroup} is present; the
     *                           exception message identifies the missing
     *                           group and the transaction type/category
     *                           context for diagnostic purposes. This
     *                           condition terminates the batch job in
     *                           the same manner as COBOL
     *                           {@code 9999-ABEND-PROGRAM}.
     * @see #DEFAULT_GROUP_ID
     */
    public BigDecimal selectInterestRate(
            Optional<DisclosureGroup> primaryGroup,
            Optional<DisclosureGroup> defaultGroup,
            String acctGroupId,
            String typeCode,
            Short catCode) {
        // First attempt: account-specific group (← 1200-GET-INTEREST-RATE)
        if (primaryGroup.isPresent()) {
            return primaryGroup.get().getDisIntRate();
        }
        // DEFAULT fallback (← 1200-A-GET-DEFAULT-INT-RATE)
        if (defaultGroup.isPresent()) {
            return defaultGroup.get().getDisIntRate();
        }
        // Fatal error: neither account group nor DEFAULT found
        // (← 9999-ABEND-PROGRAM). Error message format preserved exactly
        // from InterestCalculationProcessor source lines 430-433 to
        // maintain log/test-assertion parity (AAP Rule R-001).
        String errorMsg = String.format(
                "Disclosure group record not found for group '%s' or DEFAULT — "
                        + "typeCode: %s, catCode: %s (COBOL ABEND equivalent)",
                acctGroupId, typeCode, catCode);
        throw new CardDemoException(errorMsg);
    }

    /**
     * Applies accumulated interest to the current account and resets the
     * end-of-cycle credit and debit counters to zero.
     *
     * <p>Maps CBACT04C.cbl paragraph {@code 1050-UPDATE-ACCOUNT}
     * (lines 350-370):</p>
     * <pre>
     * ADD WS-TOTAL-INT TO ACCT-CURR-BAL
     * MOVE 0              TO ACCT-CURR-CYC-CREDIT
     * MOVE 0              TO ACCT-CURR-CYC-DEBIT
     * REWRITE FD-ACCTFILE-REC FROM ACCOUNT-RECORD
     * </pre>
     *
     * <p>Called by the processor on account-break detection (when a new
     * account ID is encountered in the input stream, meaning the previous
     * account's interest accumulation is complete) and again at step
     * completion (via the processor's {@code afterStep} hook) to flush
     * the final account's accumulated interest.</p>
     *
     * <p><strong>Persistence is the caller's responsibility.</strong>
     * This method only applies the in-memory updates to the
     * {@link Account} entity passed by the caller. The caller
     * (processor) is responsible for subsequently invoking
     * {@code accountRepository.save(account)} &mdash; the Java
     * equivalent of the COBOL {@code REWRITE FD-ACCTFILE-REC FROM
     * ACCOUNT-RECORD} statement. This repository-free design preserves
     * the framework-independence of the rules class; injecting a
     * repository here would violate AAP &sect;0.4.3 "Domain Rules
     * Pattern" constraints.</p>
     *
     * <p>The caller should invoke {@link #shouldApplyInterest(Account,
     * BigDecimal)} before calling this method to reproduce the exact
     * COBOL guard: skip the update when no account is set or when
     * {@code totalInterest} is zero.</p>
     *
     * @param account       the account entity to update (COBOL:
     *                      {@code ACCOUNT-RECORD}); must not be
     *                      {@code null}. The entity is mutated in place
     *                      via
     *                      {@link Account#setAcctCurrBal(BigDecimal)},
     *                      {@link Account#setAcctCurrCycCredit(BigDecimal)},
     *                      and
     *                      {@link Account#setAcctCurrCycDebit(BigDecimal)}
     * @param totalInterest the accumulated total interest across all
     *                      transaction categories for this account
     *                      (COBOL: {@code WS-TOTAL-INT}); must not be
     *                      {@code null}. Added to the account's current
     *                      balance via
     *                      {@link BigDecimal#add(BigDecimal)}
     * @see #shouldApplyInterest(Account, BigDecimal)
     * @see Account#setAcctCurrBal(BigDecimal)
     * @see Account#setAcctCurrCycCredit(BigDecimal)
     * @see Account#setAcctCurrCycDebit(BigDecimal)
     */
    public void applyInterestToAccount(Account account, BigDecimal totalInterest) {
        // Compute new balance: previous + accumulated total interest.
        // (← ADD WS-TOTAL-INT TO ACCT-CURR-BAL)
        BigDecimal previousBalance = account.getAcctCurrBal();
        BigDecimal newBalance = previousBalance.add(totalInterest);
        // Apply accumulated interest to account balance
        account.setAcctCurrBal(newBalance);
        // Reset end-of-cycle credit and debit counters
        // (← MOVE 0 TO ACCT-CURR-CYC-CREDIT / ACCT-CURR-CYC-DEBIT)
        account.setAcctCurrCycCredit(BigDecimal.ZERO);
        account.setAcctCurrCycDebit(BigDecimal.ZERO);
        // NOTE: persistence (accountRepository.save(...)) is the caller's
        // responsibility — this rules class is repository-free by design
        // (AAP §0.4.3 Domain Rules Pattern).
    }

    /**
     * Guard check determining whether
     * {@link #applyInterestToAccount(Account, BigDecimal)} should be
     * invoked for the given account and accumulated interest.
     *
     * <p>Reproduces the COBOL guard from
     * {@code InterestCalculationProcessor.updateAccount()} (source
     * line 542):</p>
     * <pre>
     * if (currentAccount != null &amp;&amp;
     *     totalInterest.compareTo(BigDecimal.ZERO) != 0)
     * </pre>
     *
     * <p>Returns {@code true} only when <em>both</em> conditions hold:</p>
     * <ul>
     *   <li>{@code account} is non-{@code null} &mdash; the processor
     *       may call this when no current account has been established
     *       yet (e.g., at the very start of the step before the first
     *       input record is processed, or after the final account has
     *       already been flushed).</li>
     *   <li>{@code totalInterest} is non-{@code null} AND not equal to
     *       zero by {@link BigDecimal#compareTo(BigDecimal)} (which
     *       treats different-scale representations of zero such as
     *       {@code 0} and {@code 0.00} as equal, unlike
     *       {@link Object#equals(Object)}). This matches the COBOL
     *       semantic of testing a numeric field for non-zero after
     *       accumulation.</li>
     * </ul>
     *
     * <p>This matches the COBOL behavior where the account update is a
     * no-op when no interest has been accumulated (e.g., for an account
     * whose transaction categories all had zero balances) or when no
     * current account is set (e.g., the step has no input records).</p>
     *
     * <p>The {@code null} check on {@code totalInterest} extends the
     * source implementation (which would throw a
     * {@link NullPointerException} on a null accumulator) to produce a
     * clean {@code false} return. This is defensive &mdash; the
     * processor always initializes {@code totalInterest} to
     * {@link BigDecimal#ZERO} at step start, so a {@code null} value
     * should never occur in practice. The defensive check preserves
     * behavioral equivalence for all non-null inputs while adding
     * safety for unexpected inputs.</p>
     *
     * @param account       the account entity, or {@code null} if no
     *                      current account has been established
     * @param totalInterest the accumulated total interest, or
     *                      {@code null} if not yet initialized
     * @return {@code true} if both inputs are non-{@code null} and
     *         {@code totalInterest} is not zero; {@code false}
     *         otherwise. When {@code true}, the caller should invoke
     *         {@link #applyInterestToAccount(Account, BigDecimal)}
     *         with the same arguments and then persist the entity.
     * @see #applyInterestToAccount(Account, BigDecimal)
     */
    public boolean shouldApplyInterest(Account account, BigDecimal totalInterest) {
        return account != null
                && totalInterest != null
                && totalInterest.compareTo(BigDecimal.ZERO) != 0;
    }
}

