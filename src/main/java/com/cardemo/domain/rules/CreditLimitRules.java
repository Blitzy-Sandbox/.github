package com.cardemo.domain.rules;

import com.cardemo.exception.CreditLimitExceededException;
import com.cardemo.model.entity.Account;

import java.math.BigDecimal;

import org.springframework.stereotype.Component;

/**
 * Domain-layer business rules class that centralizes credit-limit checking
 * logic for the CardDemo platform.
 *
 * <p>This class is the <strong>foundational rules component</strong> of the
 * {@code com.cardemo.domain.rules} package. It consolidates credit-limit
 * validation semantics that were previously scattered across two procedural
 * contexts in the legacy-translated codebase:</p>
 *
 * <ol>
 *   <li><strong>Online bill-payment context</strong> &mdash;
 *       {@link com.cardemo.service.billing.BillPaymentService}, mapping to
 *       COBOL program {@code COBIL00C.cbl} (online bill payment). The online
 *       check compares the current account balance directly to the credit
 *       limit: when {@code ACCT-CURR-BAL > ACCT-CREDIT-LIMIT} the payment is
 *       rejected.</li>
 *   <li><strong>Batch transaction-posting context</strong> &mdash;
 *       {@link com.cardemo.batch.processors.TransactionPostingProcessor},
 *       mapping to COBOL program {@code CBTRN02C.cbl} paragraph
 *       {@code 1500-VALIDATE-CRED-LIMIT} (batch daily transaction posting).
 *       The batch check uses the <em>projected cycle balance</em>
 *       {@code WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT +
 *       DALYTRAN-AMT} and rejects when
 *       {@code ACCT-CREDIT-LIMIT < WS-TEMP-BAL}.</li>
 * </ol>
 *
 * <h3>Why Two Distinct Semantics?</h3>
 * <p>Although both COBOL programs address the same business concern
 * (preventing over-limit credit usage), they apply subtly different
 * comparisons. The <em>online</em> context compares the <em>current</em>
 * balance (a snapshot of outstanding credit owed) to the limit; the
 * <em>batch</em> context compares the limit against a <em>forward-looking</em>
 * projected balance that accounts for the transaction being posted plus the
 * cumulative cycle credit/debit totals. Preserving both forms in separate
 * methods &mdash; rather than unifying them under conditional logic &mdash;
 * maintains direct traceability to each COBOL program's intent and upholds
 * AAP Rule R-001 &ldquo;No business logic rewriting&rdquo;.</p>
 *
 * <h3>Design Constraints (AAP &sect;0.4.3, folder-scope requirements)</h3>
 * <ul>
 *   <li><strong>Stateless</strong> &mdash; no mutable instance fields; every
 *       method is a pure function of its parameters (optionally plus the
 *       current timestamp, which is not used here).</li>
 *   <li><strong>Framework-independent</strong> &mdash; imports only JDK types
 *       plus {@link Component} for Spring dependency-injection stereotype.
 *       Does <em>not</em> import {@code org.springframework.batch.*},
 *       {@code org.springframework.data.*},
 *       {@code org.springframework.transaction.*}, or any
 *       {@code com.cardemo.repository.*} interface.</li>
 *   <li><strong>No repository injection</strong> &mdash; the entity
 *       ({@link Account}) is received as a pre-resolved parameter; this
 *       class never performs data access. Callers own persistence.</li>
 *   <li><strong>BigDecimal via {@link BigDecimal#compareTo(BigDecimal)}
 *       only</strong> (AAP &sect;0.8.2) &mdash; never
 *       {@link BigDecimal#equals(Object)} or {@code ==}. {@code compareTo()}
 *       ignores scale differences (e.g., {@code 100.00} vs {@code 100.0}),
 *       matching COBOL numeric equivalence for COMP-3 packed-decimal
 *       fields.</li>
 *   <li><strong>COBOL traceability preserved</strong> (AAP Rule R-004)
 *       &mdash; every method's Javadoc references the originating COBOL
 *       program and, where applicable, the paragraph name.</li>
 * </ul>
 *
 * <h3>Thread Safety</h3>
 * <p>All methods are pure functions over immutable inputs ({@link BigDecimal}
 * is immutable; {@link Account} fields read here are read-only accessors).
 * The class has no state, so a single shared instance is safe for concurrent
 * invocation from any number of request-handling or batch-processing threads.
 * Spring instantiates a single {@code CreditLimitRules} bean per application
 * context.</p>
 *
 * <h3>Consumers (post-refactoring)</h3>
 * <ul>
 *   <li>{@link com.cardemo.service.billing.BillPaymentService} &rarr;
 *       invokes {@link #ensureWithinOnlineCreditLimit(String, BigDecimal,
 *       BigDecimal)} during {@code processPayment} Step 4 to throw
 *       {@link CreditLimitExceededException} when the current balance
 *       exceeds the limit.</li>
 *   <li>{@code com.cardemo.domain.rules.TransactionPostingRules} (created
 *       subsequently in this package) &rarr; invokes
 *       {@link #isWithinBatchCreditLimit(Account, BigDecimal)} as Stage 3 of
 *       the batch 4-stage validation cascade. The batch path uses a boolean
 *       predicate (not a throwing method) because the processor records
 *       rejections in a {@code List<RejectionResult>} rather than raising
 *       exceptions.</li>
 * </ul>
 *
 * <h3>Reject Code Mapping</h3>
 * <p>Credit-limit rejections carry reject code
 * {@link CreditLimitExceededException#REJECT_CODE} ({@code 102}) and error
 * code {@code "CREDIT"} per {@link CreditLimitExceededException}. Both the
 * online exception path and the batch rejection-list path use the same
 * reject-code value, preserving 1:1 correspondence with the COBOL reject
 * code from {@code CBTRN02C.cbl}.</p>
 *
 * <h3>Behavioral Parity (AAP Rule R-001)</h3>
 * <p>Every method in this class produces identical accept/reject outcomes
 * for identical inputs relative to the pre-refactoring source code. No
 * comparison operator has been altered, no null-handling branch has been
 * added or removed, and the argument order of the
 * {@link CreditLimitExceededException} constructor call is preserved
 * verbatim &mdash; including the pre-existing pattern where the
 * transaction-amount argument is set equal to the current-balance argument
 * (see {@link #ensureWithinOnlineCreditLimit(String, BigDecimal,
 * BigDecimal)}).</p>
 *
 * @see com.cardemo.service.billing.BillPaymentService
 * @see com.cardemo.batch.processors.TransactionPostingProcessor
 * @see CreditLimitExceededException
 * @see Account
 */
@Component
public final class CreditLimitRules {

    /**
     * Default no-argument constructor for Spring bean instantiation.
     *
     * <p>This class carries no injected dependencies &mdash; every required
     * input is passed as a method parameter. Spring creates a single
     * {@code CreditLimitRules} bean per application context which is shared
     * across all consumers.</p>
     */
    public CreditLimitRules() {
        // Intentionally empty: stateless rules class has no initialization.
    }

    /**
     * Checks whether the current account balance is within the configured
     * credit limit for the online bill-payment context.
     *
     * <p>Maps the COBOL {@code COBIL00C.cbl} (online bill payment) credit
     * limit validation: when {@code ACCT-CREDIT-LIMIT} is set (non-null) and
     * {@code ACCT-CURR-BAL &gt; ACCT-CREDIT-LIMIT}, the payment is rejected.
     * A {@code null} credit limit is treated as unlimited &mdash; this
     * preserves the existing {@code BillPaymentService} behavior where the
     * check was guarded with {@code creditLimit != null}.</p>
     *
     * <p>Uses {@link BigDecimal#compareTo(BigDecimal)} to compare values,
     * respecting COBOL numeric equivalence semantics (ignores scale
     * differences). The within-limit condition is
     * {@code compareTo(...) &lt;= 0}, i.e., {@code currentBalance &le;
     * creditLimit}. This matches the inverse of the source predicate
     * {@code currentBalance.compareTo(creditLimit) &gt; 0} which triggers
     * rejection.</p>
     *
     * @param currentBalance the account current balance
     *                       (COBOL: {@code ACCT-CURR-BAL}, PIC S9(10)V99
     *                       COMP-3); must be {@link BigDecimal}, never
     *                       {@code float} or {@code double}
     * @param creditLimit    the account credit limit
     *                       (COBOL: {@code ACCT-CREDIT-LIMIT}, PIC S9(10)V99
     *                       COMP-3); may be {@code null}, in which case the
     *                       account is treated as having no limit
     * @return {@code true} if the balance is within the limit (including the
     *         case where {@code creditLimit} is {@code null}); {@code false}
     *         if {@code currentBalance} strictly exceeds {@code creditLimit}
     * @see com.cardemo.service.billing.BillPaymentService
     */
    public boolean isWithinOnlineCreditLimit(BigDecimal currentBalance,
                                             BigDecimal creditLimit) {
        if (creditLimit == null) {
            // Null credit limit treated as unlimited — preserves the
            // pre-refactoring BillPaymentService guard
            // `creditLimit != null && currentBalance.compareTo(creditLimit) > 0`
            // where a null limit short-circuited the rejection branch.
            return true;
        }
        // Within limit iff currentBalance <= creditLimit.
        // compareTo returns: negative if currentBalance < creditLimit,
        //                    zero if equal (scale-ignoring),
        //                    positive if currentBalance > creditLimit.
        // Hence within-limit iff compareTo result is <= 0.
        return currentBalance.compareTo(creditLimit) <= 0;
    }

    /**
     * Validates the online credit limit and throws
     * {@link CreditLimitExceededException} when the check fails.
     *
     * <p>Replicates the exact exception-construction pattern from
     * {@link com.cardemo.service.billing.BillPaymentService} Step 4 of
     * {@code processPayment}:</p>
     * <pre>
     * throw new CreditLimitExceededException(
     *         accountId, currentBalance, creditLimit, currentBalance);
     * </pre>
     *
     * <p>Per AAP Rule R-001 (&ldquo;No business logic rewriting&rdquo;), the
     * argument pattern is preserved <em>verbatim</em> from the source: the
     * second constructor argument ({@code transactionAmount}) is set equal
     * to {@code currentBalance}, matching the pre-refactoring
     * {@code BillPaymentService} behavior. Any modification to this argument
     * pattern is explicitly out of scope for this refactoring; the
     * responsibility of this class is purely structural relocation, not
     * semantic correction.</p>
     *
     * <p>When {@code creditLimit} is {@code null}, no exception is thrown
     * &mdash; this mirrors {@link #isWithinOnlineCreditLimit(BigDecimal,
     * BigDecimal)} which returns {@code true} in that case.</p>
     *
     * <p>Reject code: {@link CreditLimitExceededException#REJECT_CODE}
     * ({@code 102}). Error code: {@code "CREDIT"}.</p>
     *
     * @param accountId      the account identifier associated with the
     *                       over-limit condition (used for error reporting
     *                       and correlation); must not be {@code null}
     * @param currentBalance the current account balance
     *                       (COBOL: {@code ACCT-CURR-BAL});
     *                       must not be {@code null}
     * @param creditLimit    the account credit limit
     *                       (COBOL: {@code ACCT-CREDIT-LIMIT}); may be
     *                       {@code null}, in which case this method
     *                       returns without throwing
     * @throws CreditLimitExceededException if {@code currentBalance}
     *                                       strictly exceeds
     *                                       {@code creditLimit}
     * @see com.cardemo.service.billing.BillPaymentService
     * @see #isWithinOnlineCreditLimit(BigDecimal, BigDecimal)
     */
    public void ensureWithinOnlineCreditLimit(String accountId,
                                              BigDecimal currentBalance,
                                              BigDecimal creditLimit) {
        if (!isWithinOnlineCreditLimit(currentBalance, creditLimit)) {
            // Preserve EXACT exception-construction pattern from
            // BillPaymentService.processPayment Step 4 per AAP Rule R-001.
            // The pattern (accountId, currentBalance, creditLimit,
            // currentBalance) sets the 2nd argument (transactionAmount)
            // equal to the 4th argument (currentBalance) — this reflects
            // the source code's existing behavior and is preserved
            // verbatim to maintain 100% behavioral parity.
            throw new CreditLimitExceededException(
                    accountId, currentBalance, creditLimit, currentBalance);
        }
    }

    /**
     * Computes the projected cycle balance used for batch credit-limit
     * checks (the COBOL {@code WS-TEMP-BAL} working-storage value).
     *
     * <p>Maps the COBOL {@code CBTRN02C.cbl} (batch transaction posting)
     * pre-check computation exactly:</p>
     * <pre>
     * COMPUTE WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT
     *                     - ACCT-CURR-CYC-DEBIT
     *                     + DALYTRAN-AMT
     * </pre>
     *
     * <p>The formula represents a <em>forward-looking</em> projection of
     * the account's cycle balance <em>after</em> applying the current
     * daily transaction. It accumulates the cycle's running credit total
     * (positive contributions) minus the cycle's running debit total
     * (negative contributions, stored as positive BigDecimals per COBOL
     * conventions) plus the amount of the transaction currently under
     * consideration. When this projected balance would exceed the
     * account's credit limit, the transaction is rejected with code
     * {@code 102} in the batch pipeline.</p>
     *
     * <p>The arithmetic chain {@code .subtract(...).add(...)} is preserved
     * exactly as in the source processor to avoid any reordering effect on
     * {@link BigDecimal} scale propagation &mdash; BigDecimal operations are
     * associative mathematically but can produce different trailing-zero
     * scales depending on operand order, which could (in edge cases) alter
     * string-based logging or comparison behavior downstream. Preserving
     * the chain verbatim guarantees byte-identical output.</p>
     *
     * @param account           the account entity whose cycle credit and
     *                          debit values are used in the computation;
     *                          must not be {@code null} and its
     *                          {@code acctCurrCycCredit} and
     *                          {@code acctCurrCycDebit} fields must not be
     *                          {@code null}
     * @param transactionAmount the amount of the daily transaction being
     *                          posted (COBOL: {@code DALYTRAN-AMT}); must
     *                          not be {@code null}
     * @return the projected cycle balance
     *         {@code (cycCredit - cycDebit + transactionAmount)}
     * @see com.cardemo.batch.processors.TransactionPostingProcessor
     * @see Account#getAcctCurrCycCredit()
     * @see Account#getAcctCurrCycDebit()
     */
    public BigDecimal computeProjectedCycleBalance(Account account,
                                                   BigDecimal transactionAmount) {
        // COBOL: COMPUTE WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT
        //                            - ACCT-CURR-CYC-DEBIT
        //                            + DALYTRAN-AMT
        // Chain order preserved exactly from TransactionPostingProcessor
        // Stage 3 to guarantee byte-identical BigDecimal scale propagation.
        return account.getAcctCurrCycCredit()
                .subtract(account.getAcctCurrCycDebit())
                .add(transactionAmount);
    }

    /**
     * Checks whether posting a transaction would keep the account within
     * its credit limit for the batch transaction-posting context.
     *
     * <p>Maps the COBOL {@code CBTRN02C.cbl} paragraph
     * {@code 1500-VALIDATE-CRED-LIMIT} (batch Stage 3 of the 4-stage
     * validation cascade):</p>
     * <pre>
     * COMPUTE WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT
     *                     - ACCT-CURR-CYC-DEBIT
     *                     + DALYTRAN-AMT
     * IF ACCT-CREDIT-LIMIT &lt; WS-TEMP-BAL
     *    &rarr; Reject with code 102 (CREDIT_LIMIT_EXCEEDED)
     * </pre>
     *
     * <p>The semantics of this batch check differ meaningfully from the
     * online check in {@link #isWithinOnlineCreditLimit(BigDecimal,
     * BigDecimal)}: the batch check uses the <em>projected cycle
     * balance</em> (computed via
     * {@link #computeProjectedCycleBalance(Account, BigDecimal)}) rather
     * than the current account balance. This models a forward-looking
     * over-limit detection that accounts for all pending cycle activity
     * plus the transaction about to be posted.</p>
     *
     * <p>This method returns a boolean predicate rather than throwing an
     * exception because the batch processor
     * ({@code TransactionPostingProcessor}) accumulates rejections in a
     * {@code List<RejectionResult>} for subsequent writing to a reject
     * file &mdash; it does not use exception flow for rejected
     * transactions. The online path, in contrast, uses exception flow
     * via {@link #ensureWithinOnlineCreditLimit(String, BigDecimal,
     * BigDecimal)}.</p>
     *
     * <p>The source processor assumes a non-null credit limit for batch
     * posting (COBOL VSAM-stored accounts always have a credit limit per
     * the account master record layout {@code CVACT01Y.cpy}); this method
     * preserves that assumption and will throw
     * {@link NullPointerException} if {@code account.getAcctCreditLimit()}
     * is {@code null} &mdash; matching the source behavior (AAP Rule
     * R-001: do not add defensive null checks that would alter the
     * pre-refactoring behavior).</p>
     *
     * <p>Uses {@link BigDecimal#compareTo(BigDecimal)} to compare values.
     * The within-limit condition is {@code compareTo(...) &gt;= 0}, i.e.,
     * {@code creditLimit &ge; projectedCycleBalance}. This is the inverse
     * of the source predicate
     * {@code account.getAcctCreditLimit().compareTo(tempBal) &lt; 0}
     * which triggers rejection.</p>
     *
     * @param account           the account entity (must not be
     *                          {@code null}; its
     *                          {@code acctCreditLimit},
     *                          {@code acctCurrCycCredit}, and
     *                          {@code acctCurrCycDebit} fields must not be
     *                          {@code null})
     * @param transactionAmount the amount of the daily transaction being
     *                          posted (COBOL: {@code DALYTRAN-AMT}); must
     *                          not be {@code null}
     * @return {@code true} if the account is within its credit limit
     *         after applying the transaction; {@code false} if the
     *         projected cycle balance would strictly exceed the credit
     *         limit (rejection case, reject code {@code 102})
     * @see com.cardemo.batch.processors.TransactionPostingProcessor
     * @see #computeProjectedCycleBalance(Account, BigDecimal)
     * @see Account#getAcctCreditLimit()
     */
    public boolean isWithinBatchCreditLimit(Account account,
                                            BigDecimal transactionAmount) {
        BigDecimal projectedCycleBalance =
                computeProjectedCycleBalance(account, transactionAmount);
        // COBOL: IF ACCT-CREDIT-LIMIT < WS-TEMP-BAL → reject with code 102.
        // Within limit iff creditLimit >= projectedCycleBalance,
        // i.e., creditLimit.compareTo(projectedCycleBalance) >= 0.
        // This is the logical inverse of the source rejection predicate
        // `account.getAcctCreditLimit().compareTo(tempBal) < 0`.
        return account.getAcctCreditLimit()
                .compareTo(projectedCycleBalance) >= 0;
    }
}
