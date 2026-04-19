/*
 * Copyright 2025 CardDemo Modernization Initiative.
 *
 * Licensed under the terms of the CardDemo project.  See the project LICENSE
 * file at the repository root for details.
 */
package com.cardemo.unit.domain;

import com.cardemo.domain.rules.InterestCalculationRules;
import com.cardemo.exception.CardDemoException;
import com.cardemo.model.entity.Account;
import com.cardemo.model.entity.DisclosureGroup;
import com.cardemo.model.key.DisclosureGroupId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure JUnit 5 + AssertJ unit tests for {@link InterestCalculationRules}, the domain-layer
 * rules class that encapsulates the monthly interest calculation formula extracted from
 * {@link com.cardemo.batch.processors.InterestCalculationProcessor} per AAP &sect;0.5.1.
 *
 * <p><b>Testing approach:</b> No Spring context, no Mockito — the rules class is
 * stateless and has no injected dependencies, so the test suite simply instantiates
 * {@code InterestCalculationRules} via its no-arg constructor in {@link #setUp()} and
 * drives each method directly.  All {@link java.math.BigDecimal} assertions use
 * {@code isEqualByComparingTo()} rather than {@code .equals()} so that values that
 * compare numerically equal (e.g. {@link BigDecimal#ZERO} versus
 * {@code new BigDecimal("0.00")}) are treated as equivalent despite having different
 * scales.
 *
 * <p><b>COBOL traceability (AAP Rule R-004):</b> The rules class under test preserves
 * the monthly interest formula first encoded in the original z/OS batch program
 * {@code CBACT04C.cbl} paragraph {@code 1300-COMPUTE-INTEREST}.  The formula is
 * <pre>
 *     Monthly Interest = (Category-Balance &times; Annual-Rate) / 1200
 * </pre>
 * with banker's rounding ({@link java.math.RoundingMode#HALF_EVEN}) at scale 2.  The
 * division by 1200 is the CICS/COBOL convention for converting an annual percentage
 * rate expressed as {@code PIC S9(04)V99} (interest rate field DIS-INT-RATE in the
 * copybook {@code CVACT03Y.cpy} / {@code CVTRA02Y.cpy} DIS-GROUP-RECORD) into a monthly
 * fraction applied to an account's current category balance
 * ({@code PIC S9(09)V99 COMP-3}).  Per R-001 (no business logic rewriting), the
 * formula must NOT be algebraically simplified to {@code balance &times; (rate / 1200)} —
 * the three-step multiply-then-divide ordering is authoritative for COBOL parity and
 * the {@code shouldNotUseAlgebraicSimplification()} test enforces this invariant.
 *
 * <p>Interest-rate lookup semantics (method {@code selectInterestRate}) likewise mirror
 * the COBOL {@code 1200-A-GET-DEFAULT-INT-RATE} paragraph: primary disclosure group
 * first, then DEFAULT disclosure group, then an ABEND (modelled as
 * {@link CardDemoException}) when neither record is found.
 *
 * @see com.cardemo.domain.rules.InterestCalculationRules
 * @see com.cardemo.batch.processors.InterestCalculationProcessor
 */
@DisplayName("InterestCalculationRules — CBACT04C.cbl Interest Calculation Unit Tests")
class InterestCalculationRulesTest {

    // ---------------------------------------------------------------------------------
    // Test fixture constants.  Values are aligned with the field definitions in
    // copybooks CVACT01Y (ACCOUNT-RECORD) and CVTRA02Y (DIS-GROUP-RECORD) so that the
    // fabricated fixtures satisfy the non-null column constraints on the JPA entities
    // while exercising the rules methods.
    // ---------------------------------------------------------------------------------

    /** Account identifier — ACCT-ID PIC X(11) from CVACT01Y.cpy. */
    private static final String ACCOUNT_ID = "00000000001";

    /** Primary disclosure-group identifier — DIS-ACCT-GROUP-ID PIC X(10) from CVTRA02Y.cpy. */
    private static final String GROUP_ID = "GROUP01";

    /** Fallback disclosure group identifier used when primary lookup returns empty. */
    private static final String DEFAULT_GROUP_ID = "DEFAULT";

    /** Transaction type code — DIS-TRAN-TYPE-CD PIC X(02) from CVTRA02Y.cpy. */
    private static final String TYPE_CODE = "01";

    /** Transaction category code — DIS-TRAN-CAT-CD PIC 9(04) from CVTRA02Y.cpy. */
    private static final Short CAT_CODE = (short) 1;

    /** Class under test — stateless domain rules, safe to re-instantiate per test. */
    private InterestCalculationRules interestCalculationRules;

    @BeforeEach
    void setUp() {
        // InterestCalculationRules is a pure, stateless @Component.  It has no injected
        // dependencies and no Spring context is required — the no-arg constructor is
        // sufficient for full coverage of the rules methods.
        interestCalculationRules = new InterestCalculationRules();
    }

    // =================================================================================
    // Section 1 — computeMonthlyInterest(BigDecimal categoryBalance, BigDecimal interestRate)
    //
    // Formula: categoryBalance.multiply(interestRate).divide(1200, 2, HALF_EVEN)
    //
    // The formula is the direct COBOL equivalent of the CBACT04C.cbl
    // 1300-COMPUTE-INTEREST paragraph.  Precision rules:
    //   * scale is always 2 (two decimal places, matching PIC S9(09)V99)
    //   * rounding is HALF_EVEN (banker's rounding) per Decision D-001
    //   * multiplication is performed BEFORE division to preserve precision (R-001)
    // =================================================================================

    @Test
    void shouldComputeInterestForPositiveBalanceAndPositiveRate() {
        // (10000.00 × 18.00) / 1200 = 180000.00 / 1200 = 150.00 exactly.
        BigDecimal result = interestCalculationRules.computeMonthlyInterest(
                new BigDecimal("10000.00"), new BigDecimal("18.00"));

        assertThat(result).isEqualByComparingTo(new BigDecimal("150.00"));
    }

    @Test
    void shouldReturnScaleTwoResult() {
        // (1000.00 × 12.00) / 1200 = 12000.00 / 1200 = 10.00 with scale enforced to 2.
        // The divide() call explicitly requests scale 2 so the returned BigDecimal
        // always carries two fractional digits regardless of the input scales.
        BigDecimal result = interestCalculationRules.computeMonthlyInterest(
                new BigDecimal("1000.00"), new BigDecimal("12.00"));

        assertThat(result.scale()).isEqualTo(2);
        assertThat(result).isEqualByComparingTo(new BigDecimal("10.00"));
    }

    @Test
    void shouldReturnZeroWhenBalanceIsZero() {
        // A zero balance produces zero interest regardless of the rate.  The test uses
        // a non-zero rate to prove that the multiplication short-circuits to zero.
        BigDecimal result = interestCalculationRules.computeMonthlyInterest(
                new BigDecimal("0.00"), new BigDecimal("18.00"));

        assertThat(result).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.scale()).isEqualTo(2);
    }

    @Test
    void shouldReturnZeroWhenRateIsZero() {
        // A zero rate produces zero interest regardless of the balance.  This matches
        // the COBOL behaviour of a DIS-INT-RATE column containing LOW-VALUES / zero.
        BigDecimal result = interestCalculationRules.computeMonthlyInterest(
                new BigDecimal("10000.00"), BigDecimal.ZERO);

        assertThat(result).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.scale()).isEqualTo(2);
    }

    @Test
    void shouldHandleNegativeBalance() {
        // A negative balance (credit-side category) produces negative interest when
        // multiplied by a positive rate.  ( -1000.00 × 18.00 ) / 1200 = -15.00.
        BigDecimal result = interestCalculationRules.computeMonthlyInterest(
                new BigDecimal("-1000.00"), new BigDecimal("18.00"));

        assertThat(result).isEqualByComparingTo(new BigDecimal("-15.00"));
    }

    @Test
    void shouldHandleFractionalRate() {
        // (1000.00 × 12.50) / 1200 = 12500.00 / 1200 = 10.41666...
        // HALF_EVEN rounding at scale 2: the discarded portion starts with 6 (> 5) so
        // the result rounds up to 10.42.  This is NOT a midpoint case — standard
        // rounding suffices — but it proves the formula handles non-integer rates.
        BigDecimal result = interestCalculationRules.computeMonthlyInterest(
                new BigDecimal("1000.00"), new BigDecimal("12.50"));

        assertThat(result).isEqualByComparingTo(new BigDecimal("10.42"));
        assertThat(result.scale()).isEqualTo(2);
    }

    @Test
    void shouldRoundHalfEvenDown() {
        // HALF_EVEN (banker's rounding) midpoint — exact 1.525 rounds DOWN to 1.52
        // because 2 is the nearest even digit.  Value mirrored from
        // InterestCalculationProcessorTest.process_shouldUseBankersRoundingForInterest:
        //   (183.00 × 10.00) / 1200 = 1830.00 / 1200 = 1.525 exactly → 1.52
        // A traditional HALF_UP rule would round to 1.53 — this test proves that
        // HALF_EVEN is used.
        BigDecimal result = interestCalculationRules.computeMonthlyInterest(
                new BigDecimal("183.00"), new BigDecimal("10.00"));

        assertThat(result).isEqualByComparingTo(new BigDecimal("1.52"));
        assertThat(result.scale()).isEqualTo(2);
    }

    @Test
    void shouldRoundHalfEvenUp() {
        // HALF_EVEN midpoint — exact 1.535 rounds UP to 1.54 because the preceding
        // digit (3) is odd and 4 is the nearest even digit.  Values chosen to produce
        // an exact terminating decimal at 1.535:
        //   (307.00 × 6.00) / 1200 = 1842.00 / 1200 = 1.535 exactly → 1.54
        // This complements shouldRoundHalfEvenDown() to exercise both branches of the
        // banker's rounding tie-breaker.
        BigDecimal result = interestCalculationRules.computeMonthlyInterest(
                new BigDecimal("307.00"), new BigDecimal("6.00"));

        assertThat(result).isEqualByComparingTo(new BigDecimal("1.54"));
        assertThat(result.scale()).isEqualTo(2);
    }

    @Test
    void shouldHandleLargeBalance() {
        // Boundary case for the maximum monetary precision permitted by
        // ACCT-CURR-BAL PIC S9(09)V99 (largest representable positive balance is
        // 9,999,999.99) combined with the maximum representable rate
        // DIS-INT-RATE PIC S9(04)V99 (9,999.99, but a realistic 29.99 is used here).
        //   (999999.99 × 29.99) / 1200 = 29,989,999.7001 / 1200 = 24,991.6664...
        // HALF_EVEN at scale 2: third fractional digit is 6, rounds up → 24,991.67.
        BigDecimal result = interestCalculationRules.computeMonthlyInterest(
                new BigDecimal("999999.99"), new BigDecimal("29.99"));

        assertThat(result).isEqualByComparingTo(new BigDecimal("24991.67"));
        assertThat(result.scale()).isEqualTo(2);
    }

    @Test
    void shouldReturnBigDecimalInstance() {
        // Type-safety guardrail: the production method MUST return a BigDecimal —
        // not a double, Double, float, or Float — so that monetary precision is
        // preserved end-to-end through the batch posting chain.  The Java type
        // system enforces this at compile time, but the test locks down the contract
        // for future refactors that might inadvertently widen the return type.
        BigDecimal result = interestCalculationRules.computeMonthlyInterest(
                new BigDecimal("100.00"), new BigDecimal("12.00"));

        assertThat(result).isNotNull();
        assertThat(result).isInstanceOf(BigDecimal.class);
        assertThat(result).isEqualByComparingTo(new BigDecimal("1.00"));
    }

    @Test
    void shouldNotUseAlgebraicSimplification() {
        // R-001 / AAP §0.8.5 — formula preservation invariant.  The production code
        // must evaluate the formula as:
        //     (balance × rate) / 1200
        // It must NOT simplify it to:
        //     balance × (rate / 1200)
        // because intermediate division introduces a HALF_EVEN rounding step that
        // alters precision.  Proof case: balance=10000.00, rate=18.00.
        //
        // Correct (multiply first):   (10000.00 × 18.00) / 1200 = 150.00
        // Simplified (divide first):  18.00 / 1200 (scale 2 HALF_EVEN) = 0.02
        //                             10000.00 × 0.02                  = 200.00
        //
        // The simplified answer differs by 50.00 — this test pins the correct
        // ordering by asserting the expected 150.00 value and explicitly rejecting
        // the simplified 200.00 result.
        BigDecimal result = interestCalculationRules.computeMonthlyInterest(
                new BigDecimal("10000.00"), new BigDecimal("18.00"));

        assertThat(result).isEqualByComparingTo(new BigDecimal("150.00"));
        assertThat(result).isNotEqualByComparingTo(new BigDecimal("200.00"));
    }

    // =================================================================================
    // Section 2 — selectInterestRate(Optional<DisclosureGroup> primaryGroup,
    //                                Optional<DisclosureGroup> defaultGroup,
    //                                String acctGroupId,
    //                                String typeCode,
    //                                Short catCode)
    //
    // Fallback cascade (mirrors CBACT04C.cbl 1200-A-GET-DEFAULT-INT-RATE paragraph):
    //   1. If primary disclosure-group record is present → return its DIS-INT-RATE.
    //   2. Else if DEFAULT disclosure-group record is present → return its rate.
    //   3. Else → throw CardDemoException (COBOL ABEND equivalent).
    // =================================================================================

    @Test
    void shouldReturnPrimaryRateWhenPresent() {
        // Both primary and DEFAULT disclosure groups are present, with different rates.
        // The method must prefer the primary (GROUP01) rate and ignore DEFAULT.
        DisclosureGroup primary = disclosureGroupWith(
                GROUP_ID, TYPE_CODE, CAT_CODE, new BigDecimal("18.00"));
        DisclosureGroup defaultGroup = disclosureGroupWith(
                DEFAULT_GROUP_ID, TYPE_CODE, CAT_CODE, new BigDecimal("12.00"));

        BigDecimal rate = interestCalculationRules.selectInterestRate(
                Optional.of(primary), Optional.of(defaultGroup),
                GROUP_ID, TYPE_CODE, CAT_CODE);

        assertThat(rate).isEqualByComparingTo(new BigDecimal("18.00"));
    }

    @Test
    void shouldFallbackToDefaultRateWhenPrimaryAbsent() {
        // Primary lookup returned empty (matches COBOL DIS-GROUP-RECORD "record not
        // found" scenario).  The fallback must return the DEFAULT group's DIS-INT-RATE.
        DisclosureGroup defaultGroup = disclosureGroupWith(
                DEFAULT_GROUP_ID, TYPE_CODE, CAT_CODE, new BigDecimal("12.00"));

        BigDecimal rate = interestCalculationRules.selectInterestRate(
                Optional.empty(), Optional.of(defaultGroup),
                GROUP_ID, TYPE_CODE, CAT_CODE);

        assertThat(rate).isEqualByComparingTo(new BigDecimal("12.00"));
    }

    @Test
    void shouldThrowAbendWhenBothPrimaryAndDefaultAbsent() {
        // Neither primary nor DEFAULT disclosure group can be resolved.  In the
        // original COBOL program this condition caused an ABEND with a specific
        // error message identifying the offending group/type/category triple; the
        // Java port raises CardDemoException with the same context embedded in the
        // message so that downstream observability (structured logs + alerting)
        // can surface the triple to the on-call team.
        assertThatThrownBy(() -> interestCalculationRules.selectInterestRate(
                Optional.empty(), Optional.empty(),
                GROUP_ID, TYPE_CODE, CAT_CODE))
                .isInstanceOf(CardDemoException.class)
                .hasMessageContaining(GROUP_ID)
                .hasMessageContaining("typeCode: " + TYPE_CODE)
                .hasMessageContaining("catCode: " + CAT_CODE);
    }

    // =================================================================================
    // Section 3 — applyInterestToAccount(Account account, BigDecimal totalInterest)
    //
    // Mutates the supplied Account entity in-place:
    //   1. account.setAcctCurrBal(currentBal.add(totalInterest))
    //   2. account.setAcctCurrCycCredit(BigDecimal.ZERO)
    //   3. account.setAcctCurrCycDebit(BigDecimal.ZERO)
    //
    // The cycle-credit / cycle-debit reset is part of the COBOL monthly cycle-close
    // procedure and is invariant across all CVACT01Y ACCOUNT-RECORD updates made
    // during interest posting.
    // =================================================================================

    @Test
    void shouldAddTotalInterestToAccountBalance() {
        // Starting balance 1000.00 + interest 50.00 → 1050.00.
        Account account = accountWith(
                new BigDecimal("1000.00"),
                new BigDecimal("500.00"),
                new BigDecimal("200.00"));

        interestCalculationRules.applyInterestToAccount(account, new BigDecimal("50.00"));

        assertThat(account.getAcctCurrBal())
                .isEqualByComparingTo(new BigDecimal("1050.00"));
    }

    @Test
    void shouldResetCurrentCycleCreditToZero() {
        // Any non-zero starting cycCredit must be reset to zero after interest posting —
        // this matches the COBOL monthly cycle-close convention where current-cycle
        // aggregates are moved to cycle-to-date fields and cleared for the next cycle.
        Account account = accountWith(
                new BigDecimal("1000.00"),
                new BigDecimal("500.00"),
                new BigDecimal("200.00"));

        interestCalculationRules.applyInterestToAccount(account, new BigDecimal("50.00"));

        assertThat(account.getAcctCurrCycCredit()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void shouldResetCurrentCycleDebitToZero() {
        // Mirror of shouldResetCurrentCycleCreditToZero() for the debit field.  Both
        // cycle aggregates are reset unconditionally regardless of interest amount.
        Account account = accountWith(
                new BigDecimal("1000.00"),
                new BigDecimal("500.00"),
                new BigDecimal("200.00"));

        interestCalculationRules.applyInterestToAccount(account, new BigDecimal("50.00"));

        assertThat(account.getAcctCurrCycDebit()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void shouldHandleZeroInterest() {
        // Zero interest must leave the balance unchanged, yet still reset both cycle
        // aggregates to zero.  This matches the COBOL semantics where the cycle-close
        // paragraph always runs even if the interest category produced zero interest.
        Account account = accountWith(
                new BigDecimal("1000.00"),
                new BigDecimal("500.00"),
                new BigDecimal("200.00"));

        interestCalculationRules.applyInterestToAccount(account, BigDecimal.ZERO);

        assertThat(account.getAcctCurrBal())
                .isEqualByComparingTo(new BigDecimal("1000.00"));
        assertThat(account.getAcctCurrCycCredit()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(account.getAcctCurrCycDebit()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void shouldHandleNegativeInterest() {
        // Negative total interest (produced by negative category balances that
        // aggregate to a net credit) must subtract from the balance and still reset
        // the cycle aggregates.  1000.00 + (-50.00) = 950.00.
        Account account = accountWith(
                new BigDecimal("1000.00"),
                new BigDecimal("500.00"),
                new BigDecimal("200.00"));

        interestCalculationRules.applyInterestToAccount(account, new BigDecimal("-50.00"));

        assertThat(account.getAcctCurrBal())
                .isEqualByComparingTo(new BigDecimal("950.00"));
        assertThat(account.getAcctCurrCycCredit()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(account.getAcctCurrCycDebit()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // =================================================================================
    // Section 4 — shouldApplyInterest(Account account, BigDecimal totalInterest)
    //
    // Guard check: returns true if the account is non-null AND totalInterest is
    // numerically non-zero (compared via compareTo so that BigDecimal.ZERO and
    // new BigDecimal("0.00") are treated as equivalent).
    // =================================================================================

    @Test
    void shouldReturnTrueForNonNullAccountAndNonZeroInterest() {
        // Happy path: a populated account with a positive interest amount means the
        // caller should proceed with applyInterestToAccount().
        Account account = accountWith(
                new BigDecimal("1000.00"),
                new BigDecimal("500.00"),
                new BigDecimal("200.00"));

        boolean shouldApply = interestCalculationRules.shouldApplyInterest(
                account, new BigDecimal("50.00"));

        assertThat(shouldApply).isTrue();
    }

    @Test
    void shouldReturnFalseWhenAccountIsNull() {
        // Null account — caller should skip the interest application step.  This
        // guards against NullPointerException inside applyInterestToAccount().
        boolean shouldApply = interestCalculationRules.shouldApplyInterest(
                null, new BigDecimal("50.00"));

        assertThat(shouldApply).isFalse();
    }

    @Test
    void shouldReturnFalseWhenInterestIsZero() {
        // BigDecimal.ZERO (scale 0) — compareTo against ZERO returns 0, so the guard
        // declines to apply interest.  Matches the COBOL optimisation that skips the
        // cycle-close update when the account had no interest-bearing activity.
        Account account = accountWith(
                new BigDecimal("1000.00"),
                new BigDecimal("500.00"),
                new BigDecimal("200.00"));

        boolean shouldApply = interestCalculationRules.shouldApplyInterest(
                account, BigDecimal.ZERO);

        assertThat(shouldApply).isFalse();
    }

    @Test
    void shouldReturnFalseWhenInterestIsZeroWithScale() {
        // Scaled zero (new BigDecimal("0.00"), scale 2) — the guard must still return
        // false.  This test verifies that the implementation uses compareTo() (which
        // ignores scale) rather than equals() (which does not), preventing a latent
        // bug where a scale-2 zero would incorrectly be treated as non-zero interest.
        Account account = accountWith(
                new BigDecimal("1000.00"),
                new BigDecimal("500.00"),
                new BigDecimal("200.00"));

        boolean shouldApply = interestCalculationRules.shouldApplyInterest(
                account, new BigDecimal("0.00"));

        assertThat(shouldApply).isFalse();
    }

    @Test
    void shouldReturnTrueWhenInterestIsNegative() {
        // Negative interest (net credit carryover) is still "non-zero" and must be
        // applied so that the balance is correctly reduced during cycle close.
        Account account = accountWith(
                new BigDecimal("1000.00"),
                new BigDecimal("500.00"),
                new BigDecimal("200.00"));

        boolean shouldApply = interestCalculationRules.shouldApplyInterest(
                account, new BigDecimal("-10.00"));

        assertThat(shouldApply).isTrue();
    }

    // =================================================================================
    // Fixture helpers
    // =================================================================================

    /**
     * Fabricates an {@link Account} populated with realistic field values that
     * satisfy the JPA entity's non-null constraints.  Only the three balance-related
     * fields are parameterised because those are the ones the rules class reads or
     * mutates.  Remaining fields use stable constants/dates to guarantee test
     * determinism across runs.
     *
     * <p>Field origins (COBOL copybook CVACT01Y, ACCOUNT-RECORD, 300-byte VSAM KSDS):
     * <ul>
     *   <li>acctId — ACCT-ID PIC X(11)</li>
     *   <li>acctActiveStatus — ACCT-ACTIVE-STATUS PIC X(01)</li>
     *   <li>acctCurrBal — ACCT-CURR-BAL PIC S9(09)V99</li>
     *   <li>acctCreditLimit — ACCT-CREDIT-LIMIT PIC S9(09)V99</li>
     *   <li>acctCashCreditLimit — ACCT-CASH-CREDIT-LIMIT PIC S9(09)V99</li>
     *   <li>acctOpenDate — ACCT-OPEN-DATE PIC X(10) (ISO date)</li>
     *   <li>acctExpDate — ACCT-EXPIRAION-DATE PIC X(10)</li>
     *   <li>acctReissueDate — ACCT-REISSUE-DATE PIC X(10)</li>
     *   <li>acctCurrCycCredit — ACCT-CURR-CYC-CREDIT PIC S9(09)V99</li>
     *   <li>acctCurrCycDebit — ACCT-CURR-CYC-DEBIT PIC S9(09)V99</li>
     *   <li>acctAddrZip — ACCT-ADDR-ZIP PIC X(10)</li>
     *   <li>acctGroupId — ACCT-GROUP-ID PIC X(10)</li>
     * </ul>
     */
    private Account accountWith(BigDecimal currBal, BigDecimal cycCredit, BigDecimal cycDebit) {
        Account account = new Account();
        account.setAcctId(ACCOUNT_ID);
        account.setAcctActiveStatus("Y");
        account.setAcctCurrBal(currBal);
        account.setAcctCreditLimit(new BigDecimal("5000.00"));
        account.setAcctCashCreditLimit(new BigDecimal("1000.00"));
        account.setAcctOpenDate(LocalDate.of(2020, 1, 1));
        account.setAcctExpDate(LocalDate.of(2030, 12, 31));
        account.setAcctReissueDate(LocalDate.of(2025, 1, 1));
        account.setAcctCurrCycCredit(cycCredit);
        account.setAcctCurrCycDebit(cycDebit);
        account.setAcctAddrZip("90210");
        account.setAcctGroupId(GROUP_ID);
        return account;
    }

    /**
     * Fabricates a {@link DisclosureGroup} (COBOL DIS-GROUP-RECORD from CVTRA02Y.cpy,
     * 50-byte VSAM record) via the 2-arg constructor.  The composite key is built
     * from the groupId/typeCode/catCode triple so that the fixture mirrors the
     * primary-key lookup behaviour exercised by the rules class.
     */
    private DisclosureGroup disclosureGroupWith(
            String groupId, String typeCode, Short catCode, BigDecimal rate) {
        DisclosureGroupId id = new DisclosureGroupId(groupId, typeCode, catCode);
        return new DisclosureGroup(id, rate);
    }
}
