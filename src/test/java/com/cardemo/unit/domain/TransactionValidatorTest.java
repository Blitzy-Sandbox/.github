/*
 * TransactionValidatorTest.java — Pure JUnit 5 + Mockito Unit Tests for the
 * Domain-Layer Transaction Field Validator.
 *
 * Tests {@link com.cardemo.domain.validation.TransactionValidator} — the
 * validator extracted from
 * {@code com.cardemo.service.transaction.TransactionAddService} per AAP
 * §0.5.1 (Domain Validation Pattern). Exercises the 11-field validation
 * cascade (type code → category code → source → description → amount →
 * origination date → processing date → merchant ID → merchant name →
 * merchant city → merchant ZIP) that mirrors the COBOL paragraph sequence
 * from {@code COTRN02C.cbl} VALIDATE-INPUT-DATA-FIELDS (lines 330-498).
 *
 * COBOL Source Traceability (AAP Rule R-004 — preserved verbatim):
 *   - app/cbl/COTRN02C.cbl (online transaction add — 783 lines; paragraphs
 *     VALIDATE-INPUT-KEY-FIELDS, VALIDATE-INPUT-DATA-FIELDS,
 *     1200-EDIT-MAP-DATA, EDIT-DATE-CCYY-MM-DD)
 *   - app/cpy/CVTRA05Y.cpy (TRAN-RECORD 350-byte VSAM record layout —
 *     TRAN-ID PIC 9(16), TRAN-TYPE-CD PIC 9(02), TRAN-CAT-CD PIC 9(04),
 *     TRAN-SOURCE PIC X(10), TRAN-DESC PIC X(100),
 *     TRAN-AMT PIC S9(09)V99 COMP-3, TRAN-MERCHANT-ID PIC 9(09),
 *     TRAN-MERCHANT-NAME PIC X(50), TRAN-MERCHANT-CITY PIC X(50),
 *     TRAN-MERCHANT-ZIP PIC X(10), TRAN-ORIG-TS PIC X(26),
 *     TRAN-PROC-TS PIC X(26))
 *   - app/cpy/COTRN02.CPY (BMS symbolic map — TTYPCDI, TCATCDI, TRNSRCI,
 *     TDESCI, TORIGDTI, TPROCDTI, MIDI, MNAMEI, MCITYI, MZIPI)
 *   - app/cpy/CSUTLDPY.cpy (date validation subprogram procedure division)
 *   - app/cpy/CSUTLDWY.cpy (date validation working-storage section)
 *
 * Testing Approach:
 *   Pure JUnit 5 + Mockito + AssertJ unit tests — NO Spring context loading,
 *   NO external integration (no database, no AWS, no HTTP). The validator's
 *   single collaborator, {@link com.cardemo.service.shared.DateValidationService},
 *   is mocked so the tests isolate the 11-field cascade logic from the
 *   underlying CCYYMMDD date parser. This mirrors the mocking approach used
 *   by {@link com.cardemo.unit.service.TransactionAddServiceTest} for the
 *   original service-layer validation, ensuring the extracted validator is
 *   exercised under identical stubbing conditions.
 *
 * Source Extraction Note:
 *   These tests mirror the validation scenarios previously embedded in
 *   {@code TransactionAddServiceTest} that covered the 11-field cascade
 *   within the monolithic service. With the extraction to the domain layer
 *   per AAP §0.5.1, the behavioral assertions for data-field validation are
 *   now the first-class responsibility of this test class.
 *
 * Error-Aggregation Pattern Note (CRITICAL):
 *   {@link com.cardemo.domain.validation.TransactionValidator} uses
 *   {@code List<String>} error accumulation joined via
 *   {@link java.util.StringJoiner} with the {@code "; "} separator and
 *   throws a single {@link com.cardemo.exception.ValidationException} via
 *   the single-String constructor (NOT the FieldError-list constructor).
 *   Multiple invalid fields therefore produce ONE exception whose
 *   {@code getMessage()} contains the semicolon-delimited aggregate text.
 *   Tests inspect this text via AssertJ's
 *   {@code hasMessageContaining(...)} rather than
 *   {@code getFieldErrors()} because {@code getFieldErrors()} returns an
 *   empty list in this aggregation mode.
 *
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 * Licensed under the Apache License, Version 2.0
 */
package com.cardemo.unit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.cardemo.domain.validation.TransactionValidator;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.TransactionDto;
import com.cardemo.service.shared.DateValidationService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Pure JUnit 5 + Mockito + AssertJ unit tests for {@link TransactionValidator}
 * — the domain-layer transaction field validator extracted from
 * {@code TransactionAddService} during the refactoring (AAP Section 0.4.1,
 * "Domain Validation Pattern").
 *
 * <p>Each test exercises exactly one branch of the 11-field validation
 * cascade originally migrated from {@code COTRN02C.cbl}
 * VALIDATE-INPUT-DATA-FIELDS (lines 330-498). Tests verify the exact error
 * message text produced by the validator for each failure mode, plus the
 * happy path and the multi-error aggregation pattern that mirrors COBOL's
 * batch-redisplay workflow (where {@code WS-ERR-FLG} is set but processing
 * continues through all field checks before reporting).</p>
 *
 * <p><b>Critical Invariants Tested:</b></p>
 * <ul>
 *   <li>Error messages use the verbatim COBOL-derived phrasing (e.g.,
 *       {@code "Type Code cannot be empty"}, {@code "Amount cannot be zero"},
 *       {@code "Merchant ID must be numeric"}).</li>
 *   <li>Multiple failures accumulate into a SINGLE
 *       {@link ValidationException} whose message joins the individual
 *       errors with the {@code "; "} separator (via
 *       {@link java.util.StringJoiner}) — NOT a list of
 *       {@code FieldError} objects.</li>
 *   <li>The cascade produces errors in deterministic field order (Type
 *       Code first → Merchant ZIP last), matching the COBOL paragraph
 *       execution order.</li>
 *   <li>Amount validation enforces the PIC S9(09)V99 COMP-3 precision
 *       envelope: non-null, non-zero, scale ≤ 2, range
 *       [-999999999.99, 999999999.99] INCLUSIVE on both ends.</li>
 *   <li>Date validation delegates to the mocked
 *       {@link DateValidationService} using the EXACT labels
 *       {@code "Origination Date"} and {@code "Processing Date"} —
 *       matching the hardcoded production strings at
 *       {@code TransactionValidator} lines 271 and 286.</li>
 *   <li>Zero amounts are REJECTED (per COBOL contract); negative amounts
 *       are ACCEPTED (they represent credits/refunds under PIC S9(09)V99
 *       signed semantics).</li>
 * </ul>
 *
 * @see TransactionValidator
 * @see com.cardemo.service.transaction.TransactionAddService
 * @see com.cardemo.unit.service.TransactionAddServiceTest
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionValidator — COTRN02C.cbl 11-Field Data Validation Unit Tests")
class TransactionValidatorTest {

    /* --------------------------------------------------------------------
     * Test fixture constants — canonical valid values used by validDto()
     * to produce a DTO that passes every one of the 11 field checks. The
     * constants align with the AAP spec for TransactionValidatorTest; they
     * intentionally differ from the reference
     * {@code TransactionAddServiceTest.TEST_*} fixtures because the domain
     * test is scoped to pure validator behavior rather than the service's
     * auto-ID generation / cross-reference resolution concerns.
     * ------------------------------------------------------------------ */

    /** Valid 2-digit numeric transaction type code (TRAN-TYPE-CD PIC 9(02)). */
    private static final String VALID_TYPE_CD = "01";

    /** Valid 4-digit numeric transaction category code (TRAN-CAT-CD PIC 9(04)). */
    private static final String VALID_CAT_CD = "0001";

    /** Valid non-blank transaction source (TRAN-SOURCE PIC X(10)). */
    private static final String VALID_SOURCE = "POS";

    /** Valid non-blank description (TRAN-DESC PIC X(100)). */
    private static final String VALID_DESC = "Valid transaction description";

    /** Valid amount within PIC S9(09)V99 range, scale=2 (TRAN-AMT). */
    private static final BigDecimal VALID_AMT = new BigDecimal("100.00");

    /** Valid origination timestamp (TRAN-ORIG-TS PIC X(26)). */
    private static final LocalDateTime VALID_ORIG_TS = LocalDateTime.of(2024, 6, 15, 10, 30, 0);

    /** Valid processing timestamp (TRAN-PROC-TS PIC X(26)). */
    private static final LocalDateTime VALID_PROC_TS = LocalDateTime.of(2024, 6, 15, 10, 30, 5);

    /** Valid 9-digit numeric merchant ID (TRAN-MERCHANT-ID PIC 9(09)). */
    private static final String VALID_MERCHANT_ID = "123456789";

    /** Valid non-blank merchant name (TRAN-MERCHANT-NAME PIC X(50)). */
    private static final String VALID_MERCHANT_NAME = "MERCHANT A";

    /** Valid non-blank merchant city (TRAN-MERCHANT-CITY PIC X(50)). */
    private static final String VALID_MERCHANT_CITY = "ANYTOWN";

    /** Valid non-blank merchant ZIP (TRAN-MERCHANT-ZIP PIC X(10)). */
    private static final String VALID_MERCHANT_ZIP = "90210";

    /**
     * Valid 16-character numeric card number (TRAN-CARD-NUM PIC X(16)).
     * Not directly validated by {@link TransactionValidator} (card
     * reference resolution is a service-layer concern) but set on the
     * fixture for payload completeness.
     */
    private static final String VALID_CARD_NUM = "4111111111111111";

    /* --------------------------------------------------------------------
     * Date-validation label constants — MUST match the hardcoded labels
     * at {@link TransactionValidator} source lines 271 and 286. Schema
     * comment suggested "Transaction Origination Date" / "Transaction
     * Processing Date" but the VERIFIED production source uses the
     * shorter form "Origination Date" / "Processing Date" and these are
     * authoritative per AAP Rule R-001 (no business logic rewriting).
     * ------------------------------------------------------------------ */

    /** Date label passed to DateValidationService for origination-date check. */
    private static final String LABEL_ORIG_DATE = "Origination Date";

    /** Date label passed to DateValidationService for processing-date check. */
    private static final String LABEL_PROC_DATE = "Processing Date";

    /* --------------------------------------------------------------------
     * Mocked collaborators and subject under test
     * ------------------------------------------------------------------ */

    /**
     * Mocked {@link DateValidationService}. Injected into
     * {@link TransactionValidator} via its constructor by
     * {@code @InjectMocks}. The {@code @BeforeEach} installs a lenient
     * stub returning a valid result for any label, so individual tests
     * only need to override behavior for the specific date label they
     * wish to fail.
     */
    @Mock
    private DateValidationService dateValidationService;

    /**
     * Subject under test — the transaction validator. Mockito's
     * {@code @InjectMocks} auto-instantiates it by invoking the
     * {@code TransactionValidator(DateValidationService)} constructor
     * with the mocked collaborator field above.
     */
    @InjectMocks
    private TransactionValidator transactionValidator;

    /**
     * Installs the baseline date-validation stub before each test. All 11
     * cascade branches either skip the DateValidationService (non-date
     * fields) or rely on this lenient default (date fields with a valid
     * LocalDateTime). Tests that wish to force a date failure override
     * the stub for a specific label using
     * {@code when(...).thenReturn(invalidDateResult(...))}.
     */
    @BeforeEach
    void setUp() {
        lenient().when(dateValidationService.validateDate(anyString(), anyString()))
                .thenReturn(validDateResult());
    }

    /* ====================================================================
     * SECTION 1 — Transaction Type Code (Field 1)
     * COBOL lines 333-345  |  TRAN-TYPE-CD PIC 9(02)  |  BMS TTYPCDI
     * ================================================================== */

    @Test
    @DisplayName("type code: empty string produces 'Type Code cannot be empty'")
    void shouldRejectWhenTypeCodeIsBlank() {
        TransactionDto dto = validDto();
        dto.setTranTypeCd("");

        assertThatThrownBy(() -> transactionValidator.validateDataFields(dto))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Type Code cannot be empty");
    }

    @Test
    @DisplayName("type code: null value produces 'Type Code cannot be empty'")
    void shouldRejectWhenTypeCodeIsNull() {
        TransactionDto dto = validDto();
        dto.setTranTypeCd(null);

        assertThatThrownBy(() -> transactionValidator.validateDataFields(dto))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Type Code cannot be empty");
    }

    @Test
    @DisplayName("type code: whitespace-only value produces 'Type Code cannot be empty'")
    void shouldRejectWhenTypeCodeIsWhitespace() {
        TransactionDto dto = validDto();
        dto.setTranTypeCd("  ");  // isBlank() helper trims then checks isEmpty()

        assertThatThrownBy(() -> transactionValidator.validateDataFields(dto))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Type Code cannot be empty");
    }

    @Test
    @DisplayName("type code: non-numeric value produces 'Type Code must be numeric'")
    void shouldRejectWhenTypeCodeHasNonNumeric() {
        TransactionDto dto = validDto();
        dto.setTranTypeCd("AB");  // TRAN-TYPE-CD PIC 9(02) requires digits only

        assertThatThrownBy(() -> transactionValidator.validateDataFields(dto))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Type Code must be numeric");
    }

    @Test
    @DisplayName("type code: mixed alphanumeric value produces 'Type Code must be numeric'")
    void shouldRejectWhenTypeCodeIsPartiallyNumeric() {
        TransactionDto dto = validDto();
        dto.setTranTypeCd("1A");  // First char is digit but second is not — strict isNumeric

        assertThatThrownBy(() -> transactionValidator.validateDataFields(dto))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Type Code must be numeric");
    }

    @Test
    @DisplayName("type code: valid 2-digit numeric '01' passes")
    void shouldAcceptValidTypeCode() {
        TransactionDto dto = validDto();
        // Explicit re-assignment to document the test's positive focus
        dto.setTranTypeCd(VALID_TYPE_CD);

        assertThatCode(() -> transactionValidator.validateDataFields(dto))
                .doesNotThrowAnyException();
    }

    /* ====================================================================
     * SECTION 2 — Transaction Category Code (Field 2)
     * COBOL lines 347-362  |  TRAN-CAT-CD PIC 9(04)  |  BMS TCATCDI
     * ================================================================== */

    @Test
    @DisplayName("category code: empty string produces 'Category Code cannot be empty'")
    void shouldRejectWhenCategoryCodeIsBlank() {
        TransactionDto dto = validDto();
        dto.setTranCatCd("");

        assertThatThrownBy(() -> transactionValidator.validateDataFields(dto))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Category Code cannot be empty");
    }

    @Test
    @DisplayName("category code: null value produces 'Category Code cannot be empty'")
    void shouldRejectWhenCategoryCodeIsNull() {
        TransactionDto dto = validDto();
        dto.setTranCatCd(null);

        assertThatThrownBy(() -> transactionValidator.validateDataFields(dto))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Category Code cannot be empty");
    }

    @Test
    @DisplayName("category code: non-numeric value produces 'Category Code must be numeric'")
    void shouldRejectWhenCategoryCodeIsNonNumeric() {
        TransactionDto dto = validDto();
        dto.setTranCatCd("ABCD");  // TRAN-CAT-CD PIC 9(04) requires digits only

        assertThatThrownBy(() -> transactionValidator.validateDataFields(dto))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Category Code must be numeric");
    }

    @Test
    @DisplayName("category code: valid 4-digit numeric '0001' passes")
    void shouldAcceptValidCategoryCode() {
        TransactionDto dto = validDto();
        dto.setTranCatCd(VALID_CAT_CD);

        assertThatCode(() -> transactionValidator.validateDataFields(dto))
                .doesNotThrowAnyException();
    }

    /* ====================================================================
     * SECTION 3 — Transaction Source (Field 3)
     * COBOL lines 364-373  |  TRAN-SOURCE PIC X(10)  |  BMS TRNSRCI
     * ================================================================== */

    @Test
    @DisplayName("source: empty string produces 'Transaction Source cannot be empty'")
    void shouldRejectWhenSourceIsBlank() {
        TransactionDto dto = validDto();
        dto.setTranSource("");

        assertThatThrownBy(() -> transactionValidator.validateDataFields(dto))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Transaction Source cannot be empty");
    }

    @Test
    @DisplayName("source: null value produces 'Transaction Source cannot be empty'")
    void shouldRejectWhenSourceIsNull() {
        TransactionDto dto = validDto();
        dto.setTranSource(null);

        assertThatThrownBy(() -> transactionValidator.validateDataFields(dto))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Transaction Source cannot be empty");
    }

    @Test
    @DisplayName("source: valid non-blank 'POS' passes")
    void shouldAcceptValidSource() {
        TransactionDto dto = validDto();
        dto.setTranSource(VALID_SOURCE);

        assertThatCode(() -> transactionValidator.validateDataFields(dto))
                .doesNotThrowAnyException();
    }

    /* ====================================================================
     * SECTION 4 — Transaction Description (Field 4)
     * COBOL lines 375-384  |  TRAN-DESC PIC X(100)  |  BMS TDESCI
     * ================================================================== */

    @Test
    @DisplayName("description: empty string produces 'Description cannot be empty'")
    void shouldRejectWhenDescriptionIsBlank() {
        TransactionDto dto = validDto();
        dto.setTranDesc("");

        assertThatThrownBy(() -> transactionValidator.validateDataFields(dto))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Description cannot be empty");
    }

    @Test
    @DisplayName("description: null value produces 'Description cannot be empty'")
    void shouldRejectWhenDescriptionIsNull() {
        TransactionDto dto = validDto();
        dto.setTranDesc(null);

        assertThatThrownBy(() -> transactionValidator.validateDataFields(dto))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Description cannot be empty");
    }

    @Test
    @DisplayName("description: valid non-blank text passes")
    void shouldAcceptValidDescription() {
        TransactionDto dto = validDto();
        dto.setTranDesc(VALID_DESC);

        assertThatCode(() -> transactionValidator.validateDataFields(dto))
                .doesNotThrowAnyException();
    }

    /* ====================================================================
     * SECTION 5 — Transaction Amount (Field 5)
     * COBOL lines 386-423  |  TRAN-AMT PIC S9(09)V99 COMP-3
     *
     * Precision envelope (per TransactionConstants):
     *   MAX_AMOUNT_SCALE       = 2
     *   MAX_TRANSACTION_AMOUNT =  999999999.99  (INCLUSIVE)
     *   MIN_TRANSACTION_AMOUNT = -999999999.99  (INCLUSIVE)
     * Zero amounts are REJECTED (COBOL contract);
     * negative amounts are ACCEPTED (credits/refunds).
     * ================================================================== */

    @Test
    @DisplayName("amount: null value produces 'Amount cannot be empty'")
    void shouldRejectWhenAmountIsNull() {
        TransactionDto dto = validDto();
        dto.setTranAmt(null);

        assertThatThrownBy(() -> transactionValidator.validateDataFields(dto))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Amount cannot be empty");
    }

    @Test
    @DisplayName("amount: BigDecimal.ZERO produces 'Amount cannot be zero'")
    void shouldRejectWhenAmountIsZero() {
        TransactionDto dto = validDto();
        dto.setTranAmt(BigDecimal.ZERO);

        assertThatThrownBy(() -> transactionValidator.validateDataFields(dto))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Amount cannot be zero");
    }

    @Test
    @DisplayName("amount: zero-valued '0.00' produces 'Amount cannot be zero' via compareTo")
    void shouldRejectWhenAmountIsZeroWithScaleTwo() {
        TransactionDto dto = validDto();
        // compareTo ignores scale so 0.00 and BigDecimal.ZERO are both zero
        dto.setTranAmt(new BigDecimal("0.00"));

        assertThatThrownBy(() -> transactionValidator.validateDataFields(dto))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Amount cannot be zero");
    }

    @Test
    @DisplayName("amount: scale 3 (100.123) produces 'Amount cannot have more than 2 decimal places'")
    void shouldRejectWhenAmountScaleExceedsMax() {
        TransactionDto dto = validDto();
        dto.setTranAmt(new BigDecimal("100.123"));  // scale 3 > MAX_AMOUNT_SCALE=2

        assertThatThrownBy(() -> transactionValidator.validateDataFields(dto))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Amount cannot have more than 2 decimal places");
    }

    @Test
    @DisplayName("amount: over-max 1000000000.00 produces 'Amount exceeds maximum allowed value of 999999999.99'")
    void shouldRejectWhenAmountExceedsMaxLimit() {
        TransactionDto dto = validDto();
        dto.setTranAmt(new BigDecimal("1000000000.00"));  // > MAX_TRANSACTION_AMOUNT

        assertThatThrownBy(() -> transactionValidator.validateDataFields(dto))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Amount exceeds maximum allowed value of 999999999.99");
    }

    @Test
    @DisplayName("amount: under-min -1000000000.00 produces 'Amount is below minimum allowed value of -999999999.99'")
    void shouldRejectWhenAmountBelowMinLimit() {
        TransactionDto dto = validDto();
        dto.setTranAmt(new BigDecimal("-1000000000.00"));  // < MIN_TRANSACTION_AMOUNT

        assertThatThrownBy(() -> transactionValidator.validateDataFields(dto))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Amount is below minimum allowed value of -999999999.99");
    }

    @Test
    @DisplayName("amount: valid positive 100.00 passes")
    void shouldAcceptPositiveAmount() {
        TransactionDto dto = validDto();
        dto.setTranAmt(new BigDecimal("100.00"));

        assertThatCode(() -> transactionValidator.validateDataFields(dto))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("amount: valid negative -100.00 passes (credits/refunds under PIC S9(09)V99 signed)")
    void shouldAcceptNegativeAmount() {
        TransactionDto dto = validDto();
        dto.setTranAmt(new BigDecimal("-100.00"));

        assertThatCode(() -> transactionValidator.validateDataFields(dto))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("amount: at-max 999999999.99 (inclusive boundary) passes")
    void shouldAcceptAmountAtMaxBoundary() {
        TransactionDto dto = validDto();
        dto.setTranAmt(new BigDecimal("999999999.99"));  // exactly MAX_TRANSACTION_AMOUNT

        assertThatCode(() -> transactionValidator.validateDataFields(dto))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("amount: at-min -999999999.99 (inclusive boundary) passes")
    void shouldAcceptAmountAtMinBoundary() {
        TransactionDto dto = validDto();
        dto.setTranAmt(new BigDecimal("-999999999.99"));  // exactly MIN_TRANSACTION_AMOUNT

        assertThatCode(() -> transactionValidator.validateDataFields(dto))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("amount: scale=2 value 99.99 passes")
    void shouldAcceptAmountWithScaleTwo() {
        TransactionDto dto = validDto();
        dto.setTranAmt(new BigDecimal("99.99"));

        assertThatCode(() -> transactionValidator.validateDataFields(dto))
                .doesNotThrowAnyException();
    }

    /* ====================================================================
     * SECTION 6 — Origination Timestamp (Field 6)
     * COBOL lines 425-445  |  TRAN-ORIG-TS PIC X(26)
     * Validator converts LocalDateTime → CCYYMMDD string via
     * DateFormatConstants.CCYYMMDD_FORMATTER and delegates validation to
     * DateValidationService.validateDate(dateStr, "Origination Date").
     * Delegates the CSUTLDTC.cbl date-validation logic.
     * ================================================================== */

    @Test
    @DisplayName("origination timestamp: null value produces 'Origination Date cannot be empty'")
    void shouldRejectWhenOrigTsIsNull() {
        TransactionDto dto = validDto();
        dto.setTranOrigTs(null);

        assertThatThrownBy(() -> transactionValidator.validateDataFields(dto))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Origination Date cannot be empty");
    }

    @Test
    @DisplayName("origination timestamp: invalid date from DateValidationService produces 'Origination Date is not valid'")
    void shouldRejectWhenOrigTsIsInvalid() {
        TransactionDto dto = validDto();
        // Override lenient stub: force the "Origination Date" label to return invalid
        when(dateValidationService.validateDate(anyString(), eq(LABEL_ORIG_DATE)))
                .thenReturn(invalidDateResult("Date is not valid"));

        assertThatThrownBy(() -> transactionValidator.validateDataFields(dto))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Origination Date is not valid");
    }

    @Test
    @DisplayName("origination timestamp: valid LocalDateTime passes via lenient validDateResult stub")
    void shouldAcceptValidOrigTs() {
        TransactionDto dto = validDto();
        dto.setTranOrigTs(VALID_ORIG_TS);

        assertThatCode(() -> transactionValidator.validateDataFields(dto))
                .doesNotThrowAnyException();
    }

    /* ====================================================================
     * SECTION 7 — Processing Timestamp (Field 7)
     * COBOL lines 447-467  |  TRAN-PROC-TS PIC X(26)
     * Validator converts LocalDateTime → CCYYMMDD string via
     * DateFormatConstants.CCYYMMDD_FORMATTER and delegates validation to
     * DateValidationService.validateDate(dateStr, "Processing Date").
     * ================================================================== */

    @Test
    @DisplayName("processing timestamp: null value produces 'Processing Date cannot be empty'")
    void shouldRejectWhenProcTsIsNull() {
        TransactionDto dto = validDto();
        dto.setTranProcTs(null);

        assertThatThrownBy(() -> transactionValidator.validateDataFields(dto))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Processing Date cannot be empty");
    }

    @Test
    @DisplayName("processing timestamp: invalid date from DateValidationService produces 'Processing Date is not valid'")
    void shouldRejectWhenProcTsIsInvalid() {
        TransactionDto dto = validDto();
        // Override lenient stub: force the "Processing Date" label to return invalid
        when(dateValidationService.validateDate(anyString(), eq(LABEL_PROC_DATE)))
                .thenReturn(invalidDateResult("Date is not valid"));

        assertThatThrownBy(() -> transactionValidator.validateDataFields(dto))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Processing Date is not valid");
    }

    @Test
    @DisplayName("processing timestamp: valid LocalDateTime passes via lenient validDateResult stub")
    void shouldAcceptValidProcTs() {
        TransactionDto dto = validDto();
        dto.setTranProcTs(VALID_PROC_TS);

        assertThatCode(() -> transactionValidator.validateDataFields(dto))
                .doesNotThrowAnyException();
    }

    /* ====================================================================
     * SECTION 8 — Merchant ID (Field 8)
     * COBOL lines 469-485  |  TRAN-MERCHANT-ID PIC 9(09)  |  BMS TMIDI
     * ================================================================== */

    @Test
    @DisplayName("merchant id: empty string produces 'Merchant ID cannot be empty'")
    void shouldRejectWhenMerchantIdIsBlank() {
        TransactionDto dto = validDto();
        dto.setTranMerchId("");

        assertThatThrownBy(() -> transactionValidator.validateDataFields(dto))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Merchant ID cannot be empty");
    }

    @Test
    @DisplayName("merchant id: null value produces 'Merchant ID cannot be empty'")
    void shouldRejectWhenMerchantIdIsNull() {
        TransactionDto dto = validDto();
        dto.setTranMerchId(null);

        assertThatThrownBy(() -> transactionValidator.validateDataFields(dto))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Merchant ID cannot be empty");
    }

    @Test
    @DisplayName("merchant id: non-numeric value produces 'Merchant ID must be numeric'")
    void shouldRejectWhenMerchantIdIsNonNumeric() {
        TransactionDto dto = validDto();
        dto.setTranMerchId("ABC123");  // TRAN-MERCHANT-ID PIC 9(09) requires digits only

        assertThatThrownBy(() -> transactionValidator.validateDataFields(dto))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Merchant ID must be numeric");
    }

    @Test
    @DisplayName("merchant id: valid 9-digit numeric '123456789' passes")
    void shouldAcceptValidMerchantId() {
        TransactionDto dto = validDto();
        dto.setTranMerchId(VALID_MERCHANT_ID);

        assertThatCode(() -> transactionValidator.validateDataFields(dto))
                .doesNotThrowAnyException();
    }

    /* ====================================================================
     * SECTION 9 — Merchant Name (Field 9)
     * COBOL lines 487-498  |  TRAN-MERCHANT-NAME PIC X(50)  |  BMS TMNAMEI
     * ================================================================== */

    @Test
    @DisplayName("merchant name: empty string produces 'Merchant Name cannot be empty'")
    void shouldRejectWhenMerchantNameIsBlank() {
        TransactionDto dto = validDto();
        dto.setTranMerchName("");

        assertThatThrownBy(() -> transactionValidator.validateDataFields(dto))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Merchant Name cannot be empty");
    }

    @Test
    @DisplayName("merchant name: null value produces 'Merchant Name cannot be empty'")
    void shouldRejectWhenMerchantNameIsNull() {
        TransactionDto dto = validDto();
        dto.setTranMerchName(null);

        assertThatThrownBy(() -> transactionValidator.validateDataFields(dto))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Merchant Name cannot be empty");
    }

    @Test
    @DisplayName("merchant name: valid non-blank text passes")
    void shouldAcceptValidMerchantName() {
        TransactionDto dto = validDto();
        dto.setTranMerchName(VALID_MERCHANT_NAME);

        assertThatCode(() -> transactionValidator.validateDataFields(dto))
                .doesNotThrowAnyException();
    }

    /* ====================================================================
     * SECTION 10 — Merchant City (Field 10)
     * COBOL lines 500-509  |  TRAN-MERCHANT-CITY PIC X(50)  |  BMS TMCITYI
     * ================================================================== */

    @Test
    @DisplayName("merchant city: empty string produces 'Merchant City cannot be empty'")
    void shouldRejectWhenMerchantCityIsBlank() {
        TransactionDto dto = validDto();
        dto.setTranMerchCity("");

        assertThatThrownBy(() -> transactionValidator.validateDataFields(dto))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Merchant City cannot be empty");
    }

    @Test
    @DisplayName("merchant city: null value produces 'Merchant City cannot be empty'")
    void shouldRejectWhenMerchantCityIsNull() {
        TransactionDto dto = validDto();
        dto.setTranMerchCity(null);

        assertThatThrownBy(() -> transactionValidator.validateDataFields(dto))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Merchant City cannot be empty");
    }

    @Test
    @DisplayName("merchant city: valid non-blank text passes")
    void shouldAcceptValidMerchantCity() {
        TransactionDto dto = validDto();
        dto.setTranMerchCity(VALID_MERCHANT_CITY);

        assertThatCode(() -> transactionValidator.validateDataFields(dto))
                .doesNotThrowAnyException();
    }

    /* ====================================================================
     * SECTION 11 — Merchant ZIP (Field 11)
     * COBOL lines 511-520  |  TRAN-MERCHANT-ZIP PIC X(10)  |  BMS TMZIPI
     * ================================================================== */

    @Test
    @DisplayName("merchant zip: empty string produces 'Merchant ZIP cannot be empty'")
    void shouldRejectWhenMerchantZipIsBlank() {
        TransactionDto dto = validDto();
        dto.setTranMerchZip("");

        assertThatThrownBy(() -> transactionValidator.validateDataFields(dto))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Merchant ZIP cannot be empty");
    }

    @Test
    @DisplayName("merchant zip: null value produces 'Merchant ZIP cannot be empty'")
    void shouldRejectWhenMerchantZipIsNull() {
        TransactionDto dto = validDto();
        dto.setTranMerchZip(null);

        assertThatThrownBy(() -> transactionValidator.validateDataFields(dto))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Merchant ZIP cannot be empty");
    }

    @Test
    @DisplayName("merchant zip: valid '90210' passes")
    void shouldAcceptValidMerchantZip() {
        TransactionDto dto = validDto();
        dto.setTranMerchZip(VALID_MERCHANT_ZIP);

        assertThatCode(() -> transactionValidator.validateDataFields(dto))
                .doesNotThrowAnyException();
    }


    /* ====================================================================
     * SECTION 12 — Error Aggregation (Multiple Fields)
     * Verifies that TransactionValidator collects ALL field errors and
     * joins them with "; " via StringJoiner rather than short-circuiting
     * on the first failure.
     * Production source: TransactionValidator.java lines 321-328
     * ================================================================== */

    @Test
    @DisplayName("aggregation: multiple invalid fields produce a single ValidationException with all errors joined by '; '")
    void shouldAggregateMultipleFieldErrors() {
        TransactionDto dto = validDto();
        dto.setTranTypeCd("");
        dto.setTranCatCd("");
        dto.setTranSource("");

        assertThatThrownBy(() -> transactionValidator.validateDataFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    String msg = ex.getMessage();
                    // All three errors must be present in the single aggregated message
                    assertThat(msg).contains("Type Code cannot be empty");
                    assertThat(msg).contains("Category Code cannot be empty");
                    assertThat(msg).contains("Transaction Source cannot be empty");
                    // "; " separator must be present between aggregated errors
                    assertThat(msg).contains("; ");
                });
    }

    @Test
    @DisplayName("aggregation: errors appear in field-cascade order (Type Code → Category Code → Source → ...)")
    void shouldDeterministicOrderInAggregatedMessage() {
        TransactionDto dto = validDto();
        // Invalidate fields that are checked in a specific cascade order
        dto.setTranTypeCd("");          // Field 1 — first in cascade
        dto.setTranCatCd("");           // Field 2
        dto.setTranSource("");          // Field 3
        dto.setTranMerchId("");         // Field 8 — later in cascade

        assertThatThrownBy(() -> transactionValidator.validateDataFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    String msg = ex.getMessage();
                    int typeCodePos = msg.indexOf("Type Code cannot be empty");
                    int categoryCodePos = msg.indexOf("Category Code cannot be empty");
                    int sourcePos = msg.indexOf("Transaction Source cannot be empty");
                    int merchantIdPos = msg.indexOf("Merchant ID cannot be empty");

                    // Guard: all four errors must be present
                    assertThat(typeCodePos).isGreaterThanOrEqualTo(0);
                    assertThat(categoryCodePos).isGreaterThanOrEqualTo(0);
                    assertThat(sourcePos).isGreaterThanOrEqualTo(0);
                    assertThat(merchantIdPos).isGreaterThanOrEqualTo(0);

                    // Field-cascade ordering: Type Code < Category Code < Source < Merchant ID
                    assertThat(typeCodePos).isLessThan(categoryCodePos);
                    assertThat(categoryCodePos).isLessThan(sourcePos);
                    assertThat(sourcePos).isLessThan(merchantIdPos);
                });
    }

    @Test
    @DisplayName("aggregation: single field error produces message with NO '; ' delimiter")
    void shouldNotIncludeDelimiterWhenOnlyOneErrorPresent() {
        TransactionDto dto = validDto();
        dto.setTranTypeCd("");  // Single invalid field

        assertThatThrownBy(() -> transactionValidator.validateDataFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    String msg = ex.getMessage();
                    assertThat(msg).contains("Type Code cannot be empty");
                    // StringJoiner with single element produces no delimiter
                    assertThat(msg).doesNotContain("; ");
                });
    }

    /* ====================================================================
     * SECTION 13 — Happy Path (All Fields Valid)
     * Single end-to-end success scenario where the fully-populated
     * validDto() passes every one of the 11 field checks without throwing.
     * ================================================================== */

    @Test
    @DisplayName("happy path: fully valid DTO from validDto() completes without throwing")
    void shouldAcceptWhenAllFieldsValid() {
        TransactionDto dto = validDto();

        assertThatCode(() -> transactionValidator.validateDataFields(dto))
                .doesNotThrowAnyException();
    }

    /* ====================================================================
     * SECTION 14 — Edge Cases / Boundary Tests
     * Surfaces the exact inclusive/exclusive semantics of the amount
     * precision envelope defined in TransactionConstants.
     * ================================================================== */

    @Test
    @DisplayName("edge: amount scale 3 (100.125) is rejected")
    void shouldRejectAmountWithScaleExceedingTwoDecimalPlaces() {
        TransactionDto dto = validDto();
        dto.setTranAmt(new BigDecimal("100.125"));  // scale 3 > MAX_AMOUNT_SCALE=2

        assertThatThrownBy(() -> transactionValidator.validateDataFields(dto))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Amount cannot have more than 2 decimal places");
    }

    @Test
    @DisplayName("edge: amount scale 0 (100) is accepted (scale <= 2 is valid, not == 2)")
    void shouldAcceptAmountWithFewerThanTwoDecimalPlaces() {
        TransactionDto dto = validDto();
        // Integer value — scale is 0 which is less than MAX_AMOUNT_SCALE=2
        dto.setTranAmt(new BigDecimal("100"));

        assertThatCode(() -> transactionValidator.validateDataFields(dto))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("edge: amount scale 1 (100.5) is accepted (scale <= 2 is valid)")
    void shouldAcceptAmountWithScaleOne() {
        TransactionDto dto = validDto();
        dto.setTranAmt(new BigDecimal("100.5"));  // scale 1 — valid

        assertThatCode(() -> transactionValidator.validateDataFields(dto))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("edge: amount one cent over max (1000000000.00) is rejected")
    void shouldRejectAmountAtLeastOneCentOverMax() {
        TransactionDto dto = validDto();
        // MAX_TRANSACTION_AMOUNT = 999999999.99 → one cent over is 1000000000.00
        dto.setTranAmt(new BigDecimal("1000000000.00"));

        assertThatThrownBy(() -> transactionValidator.validateDataFields(dto))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Amount exceeds maximum allowed value of 999999999.99");
    }

    @Test
    @DisplayName("edge: amount one cent under min (-1000000000.00) is rejected")
    void shouldRejectAmountAtLeastOneCentUnderMin() {
        TransactionDto dto = validDto();
        // MIN_TRANSACTION_AMOUNT = -999999999.99 → one cent below is -1000000000.00
        dto.setTranAmt(new BigDecimal("-1000000000.00"));

        assertThatThrownBy(() -> transactionValidator.validateDataFields(dto))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Amount is below minimum allowed value of -999999999.99");
    }

    /* ====================================================================
     * HELPER METHODS
     *
     * validDto()           — builds a fully-populated TransactionDto that
     *                        passes every one of the 11 field checks
     *                        (mirrors CVTRA05Y.cpy TRAN-RECORD layout)
     * validDateResult()    — constructs a DateValidationResult record that
     *                        represents a valid date outcome (mirrors
     *                        TransactionAddServiceTest.VALID_DATE_RESULT)
     * invalidDateResult()  — constructs a DateValidationResult record that
     *                        represents an invalid date outcome with a
     *                        custom fullMessage (mirrors
     *                        TransactionAddServiceTest.INVALID_DATE_RESULT)
     * ================================================================== */

    /**
     * Builds a fully-populated {@link TransactionDto} that passes every one
     * of the 11 field checks in {@link TransactionValidator#validateDataFields}.
     * Individual test methods mutate specific fields to trigger targeted
     * validation failures (e.g. {@code dto.setTranTypeCd("")}).
     *
     * <p>Field population mirrors the CVTRA05Y.cpy TRAN-RECORD layout from
     * COTRN02C.cbl. All setter names use the DTO's short-form
     * {@code setTranMerch*} convention (not {@code setTranMerchant*}).
     */
    private TransactionDto validDto() {
        TransactionDto dto = new TransactionDto();
        dto.setTranCardNum(VALID_CARD_NUM);        // TRAN-CARD-NUM PIC X(16)
        dto.setTranTypeCd(VALID_TYPE_CD);          // TRAN-TYPE-CD PIC 9(02)
        dto.setTranCatCd(VALID_CAT_CD);            // TRAN-CAT-CD PIC 9(04)
        dto.setTranSource(VALID_SOURCE);           // TRAN-SOURCE PIC X(10)
        dto.setTranDesc(VALID_DESC);               // TRAN-DESC PIC X(100)
        dto.setTranAmt(VALID_AMT);                 // TRAN-AMT PIC S9(09)V99
        dto.setTranOrigTs(VALID_ORIG_TS);          // TRAN-ORIG-TS PIC X(26)
        dto.setTranProcTs(VALID_PROC_TS);          // TRAN-PROC-TS PIC X(26)
        dto.setTranMerchId(VALID_MERCHANT_ID);     // TRAN-MERCHANT-ID PIC 9(09)
        dto.setTranMerchName(VALID_MERCHANT_NAME); // TRAN-MERCHANT-NAME PIC X(50)
        dto.setTranMerchCity(VALID_MERCHANT_CITY); // TRAN-MERCHANT-CITY PIC X(50)
        dto.setTranMerchZip(VALID_MERCHANT_ZIP);   // TRAN-MERCHANT-ZIP PIC X(10)
        return dto;
    }

    /**
     * Constructs a {@link DateValidationService.DateValidationResult} that
     * represents a valid date outcome. Mirrors
     * {@code TransactionAddServiceTest.VALID_DATE_RESULT} exactly:
     * {@code (true, 0, "Date is valid", "Date is valid", true, true, true)}.
     *
     * <p>Used by the {@link #setUp()} lenient stub so that — absent a
     * per-test override — all {@code validateDate(...)} calls return a
     * passing result and date-field validation does not spuriously fail.
     */
    private DateValidationService.DateValidationResult validDateResult() {
        return new DateValidationService.DateValidationResult(
                /* valid          */ true,
                /* severityCode   */ 0,
                /* resultMessage  */ "Date is valid",
                /* fullMessage    */ "Date is valid",
                /* yearValid      */ true,
                /* monthValid     */ true,
                /* dayValid       */ true);
    }

    /**
     * Constructs a {@link DateValidationService.DateValidationResult} that
     * represents an invalid date outcome with the supplied full message.
     * Mirrors {@code TransactionAddServiceTest.INVALID_DATE_RESULT} exactly:
     * {@code (false, 4, "Datevalue error", <message>, true, false, false)}.
     *
     * <p>The {@code severityCode = 4} matches the CSUTLDTC.cbl error severity
     * code for invalid dates. Tests override the lenient stub to force
     * specific date labels to fail:
     * {@code when(dateValidationService.validateDate(anyString(), eq(LABEL_ORIG_DATE)))
     *     .thenReturn(invalidDateResult("Date is not valid"));}
     *
     * @param message the full error message to embed in the result
     * @return a DateValidationResult with {@code valid=false}
     */
    private DateValidationService.DateValidationResult invalidDateResult(String message) {
        return new DateValidationService.DateValidationResult(
                /* valid          */ false,
                /* severityCode   */ 4,
                /* resultMessage  */ "Datevalue error",
                /* fullMessage    */ message,
                /* yearValid      */ true,
                /* monthValid     */ false,
                /* dayValid       */ false);
    }
}

