/*
 * CreditLimitRulesTest.java — Pure JUnit 5 + AssertJ Unit Tests for the
 * Domain-Layer Credit Limit Rules Class.
 *
 * Tests {@link com.cardemo.domain.rules.CreditLimitRules} — the rules class
 * extracted from {@code com.cardemo.service.billing.BillPaymentService}
 * (online path) and {@code com.cardemo.batch.processors.TransactionPostingProcessor}
 * (batch path) per AAP §0.5.1 (Domain Rules Pattern). Exercises the four
 * public methods that together encapsulate credit-limit enforcement across
 * both online and batch contexts:
 *
 *   1. isWithinOnlineCreditLimit(BigDecimal, BigDecimal) — online predicate
 *      (null creditLimit = unlimited)
 *   2. ensureWithinOnlineCreditLimit(String, BigDecimal, BigDecimal) — online
 *      throw variant (preserves R-001 exception-construction pattern where
 *      transactionAmount argument equals currentBalance argument)
 *   3. computeProjectedCycleBalance(Account, BigDecimal) — batch projection
 *      formula cycCredit.subtract(cycDebit).add(txnAmt)
 *   4. isWithinBatchCreditLimit(Account, BigDecimal) — batch overdraft check
 *      using the projected cycle balance
 *
 * COBOL Source Traceability (AAP Rule R-004 — preserved verbatim):
 *   - app/cbl/COBIL00C.cbl (online bill payment — 572 lines). The online
 *     credit-limit check compares ACCT-CURR-BAL directly to ACCT-CREDIT-LIMIT;
 *     when ACCT-CURR-BAL > ACCT-CREDIT-LIMIT the payment is rejected.
 *   - app/cbl/CBTRN02C.cbl paragraph 1500-B-LOOKUP-ACCT (lines 393-422,
 *     batch daily transaction posting). The batch credit-limit check computes
 *     WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT
 *     and rejects when ACCT-CREDIT-LIMIT < WS-TEMP-BAL (reject code 102).
 *   - app/cpy/CVACT01Y.cpy (ACCOUNT-RECORD VSAM layout) — ACCT-CREDIT-LIMIT
 *     PIC S9(10)V99 COMP-3, ACCT-CURR-BAL PIC S9(10)V99 COMP-3,
 *     ACCT-CURR-CYC-CREDIT PIC S9(10)V99 COMP-3,
 *     ACCT-CURR-CYC-DEBIT PIC S9(10)V99 COMP-3.
 *
 * Testing Approach:
 *   Pure JUnit 5 + AssertJ unit tests — NO Spring context loading, NO Mockito
 *   collaborator stubbing, NO external integration (no database, no AWS, no
 *   HTTP). {@code CreditLimitRules} has ZERO Spring-injected dependencies
 *   (stateless rules class), so direct instantiation via
 *   {@code new CreditLimitRules()} in {@code @BeforeEach} is the correct
 *   isolation pattern. The tests are the authoritative behavioral
 *   specification for CreditLimitRules and complement the Mockito-based
 *   {@link com.cardemo.unit.service.BillPaymentServiceTest} which exercises
 *   the full online bill-payment workflow with CreditLimitRules as one
 *   collaborator among others.
 *
 * Source Extraction Note:
 *   These tests mirror the credit-limit scenarios previously embedded in
 *   {@code BillPaymentServiceTest} (online path) and
 *   {@code TransactionPostingProcessorTest} (batch path). With the extraction
 *   to the domain layer per AAP §0.5.1, the behavioral assertions are now
 *   the first-class responsibility of this test class. Service and processor
 *   tests now mock the rules class rather than exercising its real logic.
 *
 * BigDecimal Precision Rules (AAP §0.8.2):
 *   All financial assertions use {@code isEqualByComparingTo()} — NEVER
 *   {@code equals()}. BigDecimal.equals() is scale-sensitive (100.00 != 100.0)
 *   whereas compareTo() correctly ignores scale differences, matching COBOL
 *   COMP-3 numeric equivalence semantics.
 *
 * R-001 Preservation Note:
 *   The {@code ensureWithinOnlineCreditLimit} production method passes
 *   {@code currentBalance} as BOTH the {@code transactionAmount} (2nd) AND
 *   the {@code currentBalance} (4th) arguments to the
 *   {@link com.cardemo.exception.CreditLimitExceededException} constructor.
 *   This is the pre-refactoring pattern from {@code BillPaymentService} and
 *   is preserved verbatim per AAP Rule R-001 ("No business logic rewriting").
 *   Tests explicitly verify this by asserting
 *   {@code transactionAmount == currentBalance} in the thrown exception.
 *
 * @see com.cardemo.domain.rules.CreditLimitRules
 * @see com.cardemo.service.billing.BillPaymentService
 * @see com.cardemo.batch.processors.TransactionPostingProcessor
 * @see com.cardemo.exception.CreditLimitExceededException
 *
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 * Licensed under the Apache License, Version 2.0
 */
package com.cardemo.unit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cardemo.domain.rules.CreditLimitRules;
import com.cardemo.exception.CreditLimitExceededException;
import com.cardemo.model.entity.Account;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pure JUnit 5 + AssertJ unit tests for {@link CreditLimitRules} —
 * validates the extracted credit-limit enforcement logic consolidated
 * from {@link com.cardemo.service.billing.BillPaymentService} (online path,
 * COBIL00C.cbl) and {@link com.cardemo.batch.processors.TransactionPostingProcessor}
 * (batch path, CBTRN02C.cbl paragraph 1500-B-LOOKUP-ACCT).
 *
 * <p>The test class is organized into five sections mapped to the four
 * public methods of {@code CreditLimitRules} plus a final boundary/edge
 * cases section:</p>
 * <ol>
 *   <li><strong>Section 1</strong> — {@code isWithinOnlineCreditLimit} —
 *       online boolean predicate; null creditLimit means unlimited.</li>
 *   <li><strong>Section 2</strong> — {@code ensureWithinOnlineCreditLimit} —
 *       online throw variant with R-001 exception-field preservation.</li>
 *   <li><strong>Section 3</strong> — {@code computeProjectedCycleBalance} —
 *       batch projection formula WS-TEMP-BAL.</li>
 *   <li><strong>Section 4</strong> — {@code isWithinBatchCreditLimit} —
 *       batch overdraft predicate using projected cycle balance.</li>
 *   <li><strong>Section 5</strong> — boundary and edge cases
 *       (large BigDecimals, cent-level precision, zero balance/zero
 *       limit).</li>
 * </ol>
 *
 * <h3>Isolation Strategy</h3>
 * <p>No Spring {@code @SpringBootTest} / {@code @DataJpaTest}, no Mockito
 * {@code @ExtendWith(MockitoExtension.class)}, no collaborator stubbing.
 * {@code CreditLimitRules} is a pure stateless rules class with no
 * dependencies; direct instantiation through the default constructor is
 * the canonical test pattern per AAP §0.4.3 (Domain Rules Pattern
 * "stateless rule classes that can be unit-tested without Spring
 * context").</p>
 *
 * <h3>Decimal Precision Rules (AAP §0.8.2)</h3>
 * <p>All monetary values use {@link BigDecimal}. All financial assertions
 * use {@code isEqualByComparingTo()}, NEVER {@code equals()}, to avoid
 * scale-sensitivity. Zero {@code float}/{@code double} usage anywhere in
 * the test class.</p>
 */
@DisplayName("CreditLimitRules — COBIL00C.cbl + CBTRN02C.cbl Credit Limit Enforcement")
class CreditLimitRulesTest {

    // -----------------------------------------------------------------------
    // Test Constants — matching COBIL00C.cbl and CBTRN02C.cbl field formats
    // (mirror BillPaymentServiceTest for consistency across the test suite)
    // -----------------------------------------------------------------------

    /**
     * Test account identifier — 11-character format matching COBOL
     * {@code ACCT-ID PIC 9(11)} (CVACT01Y.cpy). Used to construct the
     * {@link Account} test fixtures and verify the {@code accountId} field
     * on thrown {@link CreditLimitExceededException} instances.
     */
    private static final String ACCOUNT_ID = "00000000001";

    /**
     * Standard test credit limit — {@code $5,000.00} matching
     * {@link com.cardemo.unit.service.BillPaymentServiceTest} {@code CREDIT_LIMIT}
     * fixture value. Used across both online and batch test paths to
     * provide a consistent upper bound for over-limit scenarios.
     */
    private static final BigDecimal CREDIT_LIMIT = new BigDecimal("5000.00");

    /**
     * BigDecimal zero constant — used as the cycle-credit/cycle-debit/current
     * balance default in {@code accountWith} helper method when the test does
     * not exercise those fields.
     */
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    // -----------------------------------------------------------------------
    // System Under Test
    // -----------------------------------------------------------------------

    /**
     * The {@link CreditLimitRules} instance under test. Instantiated fresh
     * for each test method via {@link #setUp()} to guarantee no state leakage
     * between tests (even though {@code CreditLimitRules} is stateless by
     * design, per-test instantiation is a defensive convention).
     */
    private CreditLimitRules creditLimitRules;

    /**
     * Resets the system under test before each test method.
     *
     * <p>Creates a fresh {@link CreditLimitRules} instance via the default
     * constructor. Since {@code CreditLimitRules} has no injected
     * dependencies and carries no mutable state, this is equivalent to
     * using a shared instance &mdash; but the per-test instantiation
     * convention guards against accidental future introduction of instance
     * state.</p>
     */
    @BeforeEach
    void setUp() {
        creditLimitRules = new CreditLimitRules();
    }

    // =======================================================================
    // SECTION 1: isWithinOnlineCreditLimit(BigDecimal, BigDecimal)
    //
    // Online path (COBIL00C.cbl): boolean predicate returning true when
    // currentBalance <= creditLimit (inclusive). Null creditLimit means
    // unlimited (always returns true), preserving the pre-refactoring
    // BillPaymentService guard `creditLimit != null && currentBalance
    // .compareTo(creditLimit) > 0` where a null limit short-circuits the
    // rejection branch.
    // =======================================================================

    @Test
    @DisplayName("isWithinOnlineCreditLimit → true when balance is below limit (1000 < 5000)")
    void shouldReturnTrueWhenBalanceBelowLimit() {
        // Given: balance comfortably under the credit limit
        BigDecimal balance = new BigDecimal("1000.00");
        BigDecimal limit = new BigDecimal("5000.00");

        // When
        boolean result = creditLimitRules.isWithinOnlineCreditLimit(balance, limit);

        // Then: within limit
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("isWithinOnlineCreditLimit → true at limit boundary (5000 == 5000)")
    void shouldReturnTrueWhenBalanceEqualToLimit() {
        // Given: balance exactly at the credit limit — boundary case.
        // Semantics: `currentBalance <= creditLimit` is inclusive at equality.
        BigDecimal balance = new BigDecimal("5000.00");
        BigDecimal limit = new BigDecimal("5000.00");

        // When
        boolean result = creditLimitRules.isWithinOnlineCreditLimit(balance, limit);

        // Then: within limit (boundary inclusive)
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("isWithinOnlineCreditLimit → false when balance exceeds limit by one cent (5000.01 > 5000.00)")
    void shouldReturnFalseWhenBalanceExceedsLimit() {
        // Given: balance exceeds limit by one cent — minimum over-limit case.
        // Verifies compareTo() precision-sensitivity at the cent boundary.
        BigDecimal balance = new BigDecimal("5000.01");
        BigDecimal limit = new BigDecimal("5000.00");

        // When
        boolean result = creditLimitRules.isWithinOnlineCreditLimit(balance, limit);

        // Then: over limit (rejection case)
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("isWithinOnlineCreditLimit → false when balance significantly exceeds limit (6000 > 5000)")
    void shouldReturnFalseWhenBalanceSignificantlyExceedsLimit() {
        // Given: balance $1,000 over the limit — clearly over-limit case
        BigDecimal balance = new BigDecimal("6000.00");
        BigDecimal limit = new BigDecimal("5000.00");

        // When
        boolean result = creditLimitRules.isWithinOnlineCreditLimit(balance, limit);

        // Then: over limit
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("isWithinOnlineCreditLimit → true when creditLimit is null (unlimited)")
    void shouldReturnTrueWhenCreditLimitIsNullMeaningUnlimited() {
        // Given: null credit limit — preserves the pre-refactoring
        // BillPaymentService guard `creditLimit != null && ...` where a
        // null limit short-circuits the rejection branch (unlimited credit).
        BigDecimal balance = new BigDecimal("99999.99");
        BigDecimal limit = null;

        // When
        boolean result = creditLimitRules.isWithinOnlineCreditLimit(balance, limit);

        // Then: treated as unlimited → within limit regardless of balance
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("isWithinOnlineCreditLimit → true when balance is zero")
    void shouldReturnTrueWhenBalanceIsZero() {
        // Given: zero balance (account fully paid off)
        BigDecimal balance = new BigDecimal("0.00");
        BigDecimal limit = new BigDecimal("5000.00");

        // When
        boolean result = creditLimitRules.isWithinOnlineCreditLimit(balance, limit);

        // Then: zero is well within limit
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("isWithinOnlineCreditLimit → true when balance is negative (credit/overpayment)")
    void shouldReturnTrueWhenBalanceIsNegative() {
        // Given: negative balance represents a credit (customer overpaid
        // or received a refund) — always within limit per COBOL semantics
        // where ACCT-CURR-BAL PIC S9(10)V99 COMP-3 supports signed values.
        BigDecimal balance = new BigDecimal("-100.00");
        BigDecimal limit = new BigDecimal("5000.00");

        // When
        boolean result = creditLimitRules.isWithinOnlineCreditLimit(balance, limit);

        // Then: negative balance is always within limit
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("isWithinOnlineCreditLimit → false at sub-cent precision boundary (5000.001 > 5000.00)")
    void shouldHandlePrecisionAtLimitBoundary() {
        // Given: balance exceeds limit by 1/10 of a cent — tests that
        // BigDecimal.compareTo() is precision-sensitive and does not
        // round internally. COBOL COMP-3 fields have fixed scale (V99)
        // but BigDecimal can carry arbitrary scale; compareTo() uses the
        // mathematical value (not string form) for comparison.
        BigDecimal balance = new BigDecimal("5000.001");
        BigDecimal limit = new BigDecimal("5000.00");

        // When
        boolean result = creditLimitRules.isWithinOnlineCreditLimit(balance, limit);

        // Then: over limit (precision-sensitive comparison)
        assertThat(result).isFalse();
    }

    // =======================================================================
    // SECTION 2: ensureWithinOnlineCreditLimit(String, BigDecimal, BigDecimal)
    //
    // Online path throw variant (COBIL00C.cbl): throws CreditLimitExceededException
    // when currentBalance > creditLimit; returns normally otherwise. A null
    // creditLimit means unlimited (no throw). The thrown exception carries
    // four fields: accountId, transactionAmount, creditLimit, currentBalance.
    //
    // R-001 PRESERVATION (CRITICAL): The production code passes currentBalance
    // as BOTH the transactionAmount (2nd) AND currentBalance (4th) arguments
    // to the CreditLimitExceededException constructor. This is preserved
    // verbatim from pre-refactoring BillPaymentService behavior. Tests must
    // verify this field preservation explicitly.
    // =======================================================================

    @Test
    @DisplayName("ensureWithinOnlineCreditLimit → no throw when balance within limit")
    void shouldNotThrowWhenBalanceWithinLimit() {
        // Given: balance under the credit limit
        // When/Then: no exception should be thrown.
        // The absence of a thrown exception is the assertion here; if the
        // production method mistakenly threw on a within-limit balance,
        // this test would fail with the unexpected exception's stack trace.
        creditLimitRules.ensureWithinOnlineCreditLimit(
                ACCOUNT_ID,
                new BigDecimal("1000.00"),
                new BigDecimal("5000.00"));
    }

    @Test
    @DisplayName("ensureWithinOnlineCreditLimit → throws CreditLimitExceededException with preserved fields (R-001)")
    void shouldThrowCreditLimitExceededExceptionWhenBalanceExceedsLimit() {
        // Given: balance $1,000 over the limit
        BigDecimal currentBalance = new BigDecimal("6000.00");
        BigDecimal creditLimit = new BigDecimal("5000.00");

        // When/Then: exception is thrown with all four fields populated.
        // R-001 PRESERVATION: the production code constructs the exception
        // as `new CreditLimitExceededException(accountId, currentBalance,
        // creditLimit, currentBalance)` — passing currentBalance as BOTH
        // the 2nd arg (transactionAmount) AND the 4th arg (currentBalance).
        // This mirrors the pre-refactoring BillPaymentService.processPayment
        // Step 4 pattern and must be preserved verbatim per AAP Rule R-001.
        assertThatThrownBy(() -> creditLimitRules.ensureWithinOnlineCreditLimit(
                ACCOUNT_ID, currentBalance, creditLimit))
                .isInstanceOf(CreditLimitExceededException.class)
                .satisfies(ex -> {
                    CreditLimitExceededException cle =
                            (CreditLimitExceededException) ex;
                    // Verify accountId (1st constructor arg) is preserved
                    assertThat(cle.getAccountId()).isEqualTo(ACCOUNT_ID);
                    // Verify creditLimit (3rd constructor arg) is preserved
                    assertThat(cle.getCreditLimit())
                            .isEqualByComparingTo(creditLimit);
                    // Verify currentBalance (4th constructor arg) is preserved
                    assertThat(cle.getCurrentBalance())
                            .isEqualByComparingTo(currentBalance);
                    // R-001 CRITICAL: transactionAmount (2nd constructor arg)
                    // MUST equal currentBalance — this is the preserved
                    // legacy COBOL pattern from COBIL00C.cbl / BillPaymentService.
                    assertThat(cle.getTransactionAmount())
                            .isEqualByComparingTo(currentBalance);
                });
    }

    @Test
    @DisplayName("ensureWithinOnlineCreditLimit → no throw at limit boundary (5000 == 5000)")
    void shouldNotThrowWhenBalanceEqualsLimit() {
        // Given: balance exactly at the limit — boundary inclusive per
        // `currentBalance <= creditLimit` semantics.
        // When/Then: no exception should be thrown at the boundary.
        creditLimitRules.ensureWithinOnlineCreditLimit(
                ACCOUNT_ID,
                new BigDecimal("5000.00"),
                new BigDecimal("5000.00"));
    }

    @Test
    @DisplayName("ensureWithinOnlineCreditLimit → no throw when creditLimit is null (unlimited)")
    void shouldNotThrowWhenCreditLimitIsNull() {
        // Given: null credit limit — treated as unlimited. Any balance passes.
        // Preserves the pre-refactoring BillPaymentService guard where
        // `creditLimit != null` short-circuited the rejection branch.
        // When/Then: no exception should be thrown even with a very large balance.
        creditLimitRules.ensureWithinOnlineCreditLimit(
                ACCOUNT_ID,
                new BigDecimal("99999.99"),
                null);
    }


    // =======================================================================
    // SECTION 3: computeProjectedCycleBalance(Account, BigDecimal)
    //
    // Batch projection formula (CBTRN02C.cbl paragraph 1500-B-LOOKUP-ACCT):
    //   projectedCycleBalance = acctCurrCycCredit - acctCurrCycDebit + transactionAmount
    //
    // This is a pure arithmetic computation with no side effects. The Account
    // entity supplies the cycle-to-date credit and debit fields populated
    // during prior POSTTRAN runs; transactionAmount is the candidate value
    // for the current transaction being validated. The formula preserves
    // the exact BigDecimal arithmetic order from the pre-refactoring
    // TransactionPostingProcessor implementation.
    // =======================================================================

    @Test
    @DisplayName("computeProjectedCycleBalance → returns transactionAmount when cycle fields are zero")
    void shouldComputeProjectedCycleBalanceWithZeroCycleFields() {
        // Given: brand-new cycle with no prior credits/debits
        Account account = accountWith(
                new BigDecimal("5000.00"), // credit limit (irrelevant for projection)
                ZERO,                       // cycCredit
                ZERO);                      // cycDebit
        BigDecimal transactionAmount = new BigDecimal("100.00");

        // When: 0 - 0 + 100 = 100
        BigDecimal result =
                creditLimitRules.computeProjectedCycleBalance(account, transactionAmount);

        // Then
        assertThat(result).isEqualByComparingTo(new BigDecimal("100.00"));
    }

    @Test
    @DisplayName("computeProjectedCycleBalance → correctly applies formula cycCredit - cycDebit + txnAmt")
    void shouldComputeProjectedCycleBalanceWithPositiveCreditAndDebit() {
        // Given: mid-cycle account with both credit and debit history
        Account account = accountWith(
                new BigDecimal("5000.00"),   // credit limit
                new BigDecimal("500.00"),    // cycCredit (cycle-to-date credits)
                new BigDecimal("200.00"));   // cycDebit (cycle-to-date debits)
        BigDecimal transactionAmount = new BigDecimal("100.00");

        // When: 500 - 200 + 100 = 400
        BigDecimal result =
                creditLimitRules.computeProjectedCycleBalance(account, transactionAmount);

        // Then
        assertThat(result).isEqualByComparingTo(new BigDecimal("400.00"));
    }

    @Test
    @DisplayName("computeProjectedCycleBalance → handles negative transactionAmount (refund/credit)")
    void shouldComputeProjectedCycleBalanceWithNegativeTransaction() {
        // Given: negative transactionAmount represents a credit/refund entry.
        // COBOL TRAN-AMT PIC S9(09)V99 COMP-3 supports signed amounts; the
        // formula handles negative values via standard BigDecimal addition.
        Account account = accountWith(
                new BigDecimal("5000.00"),
                new BigDecimal("500.00"),
                new BigDecimal("200.00"));
        BigDecimal transactionAmount = new BigDecimal("-50.00");

        // When: 500 - 200 + (-50) = 250
        BigDecimal result =
                creditLimitRules.computeProjectedCycleBalance(account, transactionAmount);

        // Then
        assertThat(result).isEqualByComparingTo(new BigDecimal("250.00"));
    }

    @Test
    @DisplayName("computeProjectedCycleBalance → returns BigDecimal type with correct value")
    void shouldReturnBigDecimalTypeForProjection() {
        // Given: standard cycle state
        Account account = accountWith(
                new BigDecimal("5000.00"),
                new BigDecimal("500.00"),
                new BigDecimal("200.00"));

        // When
        BigDecimal result =
                creditLimitRules.computeProjectedCycleBalance(
                        account, new BigDecimal("100.00"));

        // Then: both type and value assertions (belt-and-suspenders)
        assertThat(result).isInstanceOf(BigDecimal.class);
        assertThat(result).isEqualByComparingTo(new BigDecimal("400.00"));
    }

    @Test
    @DisplayName("computeProjectedCycleBalance → preserves BigDecimal scale through subtract/add operations")
    void shouldPreserveScaleInProjection() {
        // Given: values with explicit two-decimal scale matching COBOL
        // COMP-3 V99 precision. BigDecimal.subtract() and .add() preserve
        // the maximum scale of either operand — verifies the computed
        // result retains sufficient scale for currency representation.
        Account account = accountWith(
                new BigDecimal("5000.00"),
                new BigDecimal("500.25"),   // two-decimal cycCredit
                new BigDecimal("100.10"));  // two-decimal cycDebit
        BigDecimal transactionAmount = new BigDecimal("25.50");

        // When: 500.25 - 100.10 + 25.50 = 425.65
        BigDecimal result =
                creditLimitRules.computeProjectedCycleBalance(account, transactionAmount);

        // Then: value is correct to two decimal places
        assertThat(result).isEqualByComparingTo(new BigDecimal("425.65"));
        // Verify scale is at least 2 (sufficient for cent-level currency)
        assertThat(result.scale()).isGreaterThanOrEqualTo(2);
    }

    // =======================================================================
    // SECTION 4: isWithinBatchCreditLimit(Account, BigDecimal)
    //
    // Batch overdraft check (CBTRN02C.cbl paragraph 1500-VALIDATE-TRAN):
    //   returns true  if acctCreditLimit >= projectedCycleBalance (within)
    //   returns false if acctCreditLimit <  projectedCycleBalance (over)
    //
    // Semantically distinct from isWithinOnlineCreditLimit:
    //   Online: balance > limit → reject         (post-posting current balance)
    //   Batch:  limit < projected → reject       (pre-posting projected balance)
    //
    // R-001 PRESERVATION: The production code does NOT null-check
    // acctCreditLimit. If credit limit is null on the Account entity, the
    // production code throws NullPointerException from compareTo(). The
    // pre-refactoring TransactionPostingProcessor also had no null guard;
    // this behavior is preserved verbatim to match COBOL parity.
    // =======================================================================

    @Test
    @DisplayName("isWithinBatchCreditLimit → true when projected balance is below limit")
    void shouldReturnTrueWhenProjectedWithinLimit() {
        // Given: projected = 500 - 200 + 100 = 400; limit = 1000; 1000 >= 400 → within
        Account account = accountWith(
                new BigDecimal("1000.00"),  // credit limit
                new BigDecimal("500.00"),   // cycCredit
                new BigDecimal("200.00"));  // cycDebit
        BigDecimal transactionAmount = new BigDecimal("100.00");

        // When
        boolean result =
                creditLimitRules.isWithinBatchCreditLimit(account, transactionAmount);

        // Then
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("isWithinBatchCreditLimit → false when projected balance exceeds limit")
    void shouldReturnFalseWhenProjectedExceedsLimit() {
        // Given: projected = 500 - 200 + 1000 = 1300; limit = 1000; 1000 < 1300 → over
        Account account = accountWith(
                new BigDecimal("1000.00"),
                new BigDecimal("500.00"),
                new BigDecimal("200.00"));
        BigDecimal transactionAmount = new BigDecimal("1000.00");

        // When
        boolean result =
                creditLimitRules.isWithinBatchCreditLimit(account, transactionAmount);

        // Then: the transaction would push cycle balance past the limit — reject
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("isWithinBatchCreditLimit → true at boundary when projected equals limit")
    void shouldReturnTrueWhenProjectedEqualsLimit() {
        // Given: projected = 500 - 200 + 700 = 1000; limit = 1000; boundary inclusive
        Account account = accountWith(
                new BigDecimal("1000.00"),
                new BigDecimal("500.00"),
                new BigDecimal("200.00"));
        BigDecimal transactionAmount = new BigDecimal("700.00");

        // When: 1000 >= 1000 holds (compareTo returns 0, which is >= 0)
        boolean result =
                creditLimitRules.isWithinBatchCreditLimit(account, transactionAmount);

        // Then: boundary inclusive — within
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("isWithinBatchCreditLimit → true when limit is very large (high-limit account)")
    void shouldReturnTrueWhenLimitIsLarge() {
        // Given: enterprise-tier credit limit of $99,999.99 (COBOL PIC 9(08)V99 max)
        Account account = accountWith(
                new BigDecimal("99999.99"),
                new BigDecimal("1000.00"),
                new BigDecimal("500.00"));
        BigDecimal transactionAmount = new BigDecimal("50.00");

        // When: projected = 1000 - 500 + 50 = 550; 99999.99 >= 550 → within
        boolean result =
                creditLimitRules.isWithinBatchCreditLimit(account, transactionAmount);

        // Then
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("isWithinBatchCreditLimit → false for one-cent overage past limit")
    void shouldReturnFalseWhenSmallOveragePastLimit() {
        // Given: projected = 0 - 0 + 1000.01 = 1000.01; limit = 1000.00; over by 1 cent
        // Verifies cent-level precision at the critical limit boundary —
        // COBOL COMP-3 V99 preserves two-decimal precision; BigDecimal
        // must match this via compareTo (not equals, which is scale-sensitive).
        Account account = accountWith(
                new BigDecimal("1000.00"),
                ZERO,
                ZERO);
        BigDecimal transactionAmount = new BigDecimal("1000.01");

        // When
        boolean result =
                creditLimitRules.isWithinBatchCreditLimit(account, transactionAmount);

        // Then: 1000.00 < 1000.01 → reject
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("isWithinBatchCreditLimit → true for all-zero cycle state with small transaction")
    void shouldHandleZeroCycleValues() {
        // Given: pristine account with no cycle activity; projected = 0 - 0 + 10 = 10
        Account account = accountWith(
                new BigDecimal("1000.00"),
                ZERO,
                ZERO);
        BigDecimal transactionAmount = new BigDecimal("10.00");

        // When
        boolean result =
                creditLimitRules.isWithinBatchCreditLimit(account, transactionAmount);

        // Then: 1000 >= 10 → within
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("isWithinBatchCreditLimit → true when transactionAmount is negative (refund)")
    void shouldHandleNegativeTransactionAmount() {
        // Given: refund/credit entry — negative transactionAmount
        // projected = 100 - 0 + (-50) = 50; limit = 1000; 1000 >= 50 → within.
        // Preserves COBOL TRAN-AMT PIC S9(09)V99 COMP-3 signed semantics.
        Account account = accountWith(
                new BigDecimal("1000.00"),
                new BigDecimal("100.00"),
                ZERO);
        BigDecimal transactionAmount = new BigDecimal("-50.00");

        // When
        boolean result =
                creditLimitRules.isWithinBatchCreditLimit(account, transactionAmount);

        // Then: a refund never increases projected balance toward overlimit — within
        assertThat(result).isTrue();
    }


    // =======================================================================
    // SECTION 5: Boundary and Edge Cases (cross-cutting across methods)
    //
    // These tests verify that precision, boundary, and degenerate-value
    // scenarios are handled correctly across both online and batch code
    // paths. They directly exercise COBOL COMP-3 precision assumptions
    // (PIC 9(09)V99 ≤ $99,999,999.99 upper bound) and boundary conditions
    // that would be silently miscompared if BigDecimal.equals() were used
    // instead of compareTo().
    // =======================================================================

    @Test
    @DisplayName("Large BigDecimal values → precision preserved for both online and batch paths")
    void shouldHandleLargeBigDecimalValues() {
        // Given: near-maximum COBOL PIC 9(09)V99 values — $999,999,999.99.
        // Tests that neither the online nor batch comparison loses precision
        // at these scales (would fail if any intermediate step used double).
        BigDecimal largeBalance = new BigDecimal("999999999.99");
        BigDecimal largeLimit = new BigDecimal("999999999.99");

        // When/Then: online — balance equals limit → within (boundary inclusive)
        assertThat(creditLimitRules.isWithinOnlineCreditLimit(
                largeBalance, largeLimit)).isTrue();

        // When/Then: online — balance one cent over limit → over
        assertThat(creditLimitRules.isWithinOnlineCreditLimit(
                new BigDecimal("1000000000.00"), largeLimit)).isFalse();

        // When/Then: batch — projected balance within large limit
        Account largeAccount = accountWith(
                largeLimit,
                new BigDecimal("100000.00"),
                new BigDecimal("50000.00"));
        assertThat(creditLimitRules.isWithinBatchCreditLimit(
                largeAccount, new BigDecimal("1000.00"))).isTrue();
    }

    @Test
    @DisplayName("Cent-level precision at boundary → online and batch paths agree at 1-cent granularity")
    void shouldHandleCentLevelPrecisionAtBoundary() {
        // Given: limit exactly $5,000.00 — COBOL COMP-3 V99 precision.
        // Tests the three critical boundary positions: 1¢ under, exactly at,
        // and 1¢ over the limit. All three must agree between compareTo()
        // and the mathematical intent regardless of BigDecimal input scale.
        BigDecimal limit = new BigDecimal("5000.00");

        // ----- Online path -----
        // 1¢ under limit → within
        assertThat(creditLimitRules.isWithinOnlineCreditLimit(
                new BigDecimal("4999.99"), limit)).isTrue();
        // Exactly at limit → within (boundary inclusive)
        assertThat(creditLimitRules.isWithinOnlineCreditLimit(
                new BigDecimal("5000.00"), limit)).isTrue();
        // 1¢ over limit → over
        assertThat(creditLimitRules.isWithinOnlineCreditLimit(
                new BigDecimal("5000.01"), limit)).isFalse();

        // ----- Batch path: crafted projected values via cycCredit/cycDebit -----
        // projected = 4999.99 (1¢ under) — within
        Account accountUnder = accountWith(limit, new BigDecimal("4999.99"), ZERO);
        assertThat(creditLimitRules.isWithinBatchCreditLimit(
                accountUnder, ZERO)).isTrue();
        // projected = 5000.00 (at limit) — within (boundary inclusive)
        Account accountAt = accountWith(limit, new BigDecimal("5000.00"), ZERO);
        assertThat(creditLimitRules.isWithinBatchCreditLimit(
                accountAt, ZERO)).isTrue();
        // projected = 5000.01 (1¢ over) — over
        Account accountOver = accountWith(limit, new BigDecimal("5000.01"), ZERO);
        assertThat(creditLimitRules.isWithinBatchCreditLimit(
                accountOver, ZERO)).isFalse();
    }

    @Test
    @DisplayName("Zero balance with zero limit → online returns true (0 ≤ 0); batch with zero txn returns true")
    void shouldHandleZeroBalanceZeroLimit() {
        // Given: degenerate account state with zero everywhere.
        // Verifies that the zero-limit edge case does not misfire: a new
        // account with no activity should be considered within limit.

        // ----- Online path: balance=0, limit=0 → 0 ≤ 0 → within -----
        assertThat(creditLimitRules.isWithinOnlineCreditLimit(
                ZERO, ZERO)).isTrue();

        // ----- Batch path: all cycle fields zero, zero transaction → within -----
        Account zeroAccount = accountWith(ZERO, ZERO, ZERO);
        assertThat(creditLimitRules.isWithinBatchCreditLimit(
                zeroAccount, ZERO)).isTrue();
    }

    // =======================================================================
    // TEST FIXTURE HELPER METHOD
    //
    // Constructs an Account entity with the three BigDecimal fields
    // consumed by CreditLimitRules (acctCreditLimit, acctCurrCycCredit,
    // acctCurrCycDebit) plus the minimum set of required entity fields
    // to keep the JPA entity in a valid state. Dates and status are set
    // to benign defaults since CreditLimitRules does not consume them.
    // =======================================================================

    /**
     * Constructs an Account entity with the credit-limit and cycle fields
     * relevant to CreditLimitRules. The remaining required entity fields
     * are populated with benign defaults (zero current balance, active
     * status "Y", open/exp dates far from today) — none of these are
     * consumed by CreditLimitRules but they keep the entity in a valid
     * state for any future test that might need additional fields.
     *
     * @param creditLimit the credit limit to assign (may be null to test
     *                    null-handling behavior where applicable)
     * @param cycCredit   cycle-to-date credit amount
     * @param cycDebit    cycle-to-date debit amount
     * @return a fully-populated Account test fixture
     */
    private Account accountWith(
            BigDecimal creditLimit,
            BigDecimal cycCredit,
            BigDecimal cycDebit) {
        Account account = new Account();
        account.setAcctId(ACCOUNT_ID);
        account.setAcctCreditLimit(creditLimit);
        account.setAcctCurrCycCredit(cycCredit);
        account.setAcctCurrCycDebit(cycDebit);
        account.setAcctCurrBal(BigDecimal.ZERO);
        account.setAcctActiveStatus("Y");
        account.setAcctOpenDate(LocalDate.of(2020, 1, 1));
        account.setAcctExpDate(LocalDate.of(2030, 12, 31));
        return account;
    }
}

