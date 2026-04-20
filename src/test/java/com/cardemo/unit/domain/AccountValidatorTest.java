/*
 * AccountValidatorTest.java — Unit Test for com.cardemo.domain.validation.AccountValidator
 *
 * Purpose:
 *   Pure JUnit 5 + Mockito unit test suite for the domain-layer
 *   {@link com.cardemo.domain.validation.AccountValidator} extracted during
 *   the AAP §0.4.1 "Domain Validation Pattern" refactoring from the
 *   monolithic {@code com.cardemo.service.account.AccountUpdateService}
 *   (lines 404–979). The validator encapsulates the account- and
 *   customer-field level validation cascade originally embedded in COBOL
 *   program {@code COACTUPC.cbl} (4,236 lines) paragraph
 *   {@code 1200-EDIT-MAP-INPUTS} with its 13 private helper paragraphs
 *   (1215-EDIT-MANDATORY, 1220-EDIT-YESNO, 1225-EDIT-ALPHA-REQD,
 *   1235-EDIT-ALPHA-OPT, 1245-EDIT-NUM-REQD, 1250-EDIT-SIGNED-9V2,
 *   1260-EDIT-US-PHONE-NUM, 1265-EDIT-US-SSN, 1270-EDIT-US-STATE-CD,
 *   1275-EDIT-FICO-SCORE, 1280-EDIT-US-STATE-ZIP-CD).
 *
 * COBOL Source Traceability:
 *   - app/cbl/COACTUPC.cbl lines 1429-1676 (1200-EDIT-MAP-INPUTS
 *     orchestration) → {@link AccountValidator#validateUpdateFields}
 *   - app/cbl/COACTUPC.cbl lines 1783-1822 (1210-EDIT-ACCOUNT) →
 *     {@link AccountValidator#validateAccountId}
 *   - app/cpy/CVACT01Y.cpy (ACCOUNT-RECORD 300-byte VSAM layout — ACCT-ID
 *     PIC 9(11), ACCT-ACTIVE-STATUS PIC X(01), ACCT-CURR-BAL PIC
 *     S9(10)V99, ACCT-CREDIT-LIMIT PIC S9(10)V99, ACCT-CASH-CREDIT-LIMIT
 *     PIC S9(10)V99, ACCT-OPEN-DATE PIC X(10), ACCT-EXPIRAION-DATE PIC
 *     X(10), ACCT-REISSUE-DATE PIC X(10), ACCT-CURR-CYC-CREDIT PIC
 *     S9(10)V99, ACCT-CURR-CYC-DEBIT PIC S9(10)V99)
 *   - app/cpy/CVCUS01Y.cpy (CUSTOMER-RECORD 500-byte VSAM layout — CUST-SSN
 *     PIC 9(09), CUST-DOB PIC X(10), CUST-FICO-CREDIT-SCORE PIC 9(03),
 *     CUST-FIRST-NAME PIC X(25), CUST-MIDDLE-NAME PIC X(25), CUST-LAST-NAME
 *     PIC X(25), CUST-ADDR-LINE-1 PIC X(50), CUST-ADDR-STATE-CD PIC X(02),
 *     CUST-ADDR-ZIP PIC X(10), CUST-ADDR-CITY PIC X(50),
 *     CUST-ADDR-COUNTRY-CD PIC X(03), CUST-PHONE-NUM-1 PIC X(15),
 *     CUST-PHONE-NUM-2 PIC X(15), CUST-EFT-ACCOUNT-ID PIC X(10),
 *     CUST-PRI-CARD-HOLDER-IND PIC X(01))
 *   - app/cpy/CSLKPCDY.cpy (88-level condition tables for NANPA area codes,
 *     US state codes, and state/ZIP prefix combinations — delegated to
 *     {@link ValidationLookupService})
 *   - app/cbl/CSUTLDTC.cbl (date validation subprogram with
 *     EDIT-DATE-CCYYMMDD and EDIT-DATE-OF-BIRTH paragraphs — delegated to
 *     {@link DateValidationService})
 *
 * Testing Approach:
 *   - Pure JUnit 5 + Mockito unit tests, NO Spring context
 *   - Uses {@code @ExtendWith(MockitoExtension.class)} for automatic
 *     {@code @Mock} and {@code @InjectMocks} wiring
 *   - Exercises the REAL {@link AccountValidator} with its two
 *     framework-independent collaborators ({@link DateValidationService},
 *     {@link ValidationLookupService}) mocked to allow deterministic
 *     stubbing per test
 *   - Test scenarios mirror {@code AccountUpdateServiceTest} (879 lines)
 *     but assert against the ACTUAL error messages produced by
 *     {@link AccountValidator} (NOT the mocked throws used by the
 *     service-level test)
 *   - Uses AssertJ fluent assertions exclusively per project convention
 *   - BigDecimal assertions use {@code isEqualByComparingTo} per AAP §0.8.2
 *     (Decision D-001: COBOL COMP-3 PACKED-DECIMAL semantics)
 *
 * Critical Invariants Tested:
 *   - {@code validateAccountId(String)}: FIRST-FAIL-THROW pattern — exactly
 *     ONE {@link ValidationException.FieldError} per failure with
 *     {@code fieldName == "acctId"} (short-circuits COBOL 1210-EDIT-ACCOUNT).
 *   - {@code validateUpdateFields(AccountDto)}: ERROR-AGGREGATION pattern —
 *     ALL field errors appended to a shared list and a single
 *     {@link ValidationException} thrown with the complete
 *     {@code List<FieldError>} (NOT string-joined like TransactionValidator).
 *   - Cross-field step 25 ({@code 1280-EDIT-US-STATE-ZIP-CD}): the
 *     state/ZIP prefix check runs only when BOTH individual state and ZIP
 *     validations passed.
 *   - Optional phone fields: blank/null phone values return silently (no
 *     error added), matching COBOL 1260-EDIT-US-PHONE-NUM guard.
 *   - BigDecimal monetary fields: validation passes for any non-null value
 *     (Java's BigDecimal type guarantees numeric format — only null check
 *     needed, matching COBOL 1250-EDIT-SIGNED-9V2 semantics).
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

import com.cardemo.domain.validation.AccountValidator;
import com.cardemo.exception.ValidationException;
import com.cardemo.exception.ValidationException.FieldError;
import com.cardemo.model.dto.AccountDto;
import com.cardemo.service.shared.DateValidationService;
import com.cardemo.service.shared.ValidationLookupService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Unit tests for {@link AccountValidator}, the domain-layer field validator
 * extracted from {@code AccountUpdateService} during the AAP refactoring.
 *
 * <p>These tests instantiate the REAL validator with Mockito-injected
 * collaborators ({@link DateValidationService} and
 * {@link ValidationLookupService}). The {@code @BeforeEach setUp()} method
 * installs {@code lenient()} stubs so that most tests use a fully-valid
 * {@code AccountDto} and selectively override individual stub behaviors to
 * force targeted failures.</p>
 *
 * <p>Test organization mirrors the 25-step validation cascade in
 * {@link AccountValidator#validateUpdateFields(AccountDto)} (Sections 2a
 * through 2p) plus a dedicated first-fail-throw section for
 * {@link AccountValidator#validateAccountId(String)} (Section 1).</p>
 *
 * @see AccountValidator
 * @see com.cardemo.service.account.AccountUpdateService (original source of extracted logic)
 * @see ValidationException
 * @see AccountDto
 * @see DateValidationService
 * @see ValidationLookupService
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AccountValidator — COACTUPC.cbl Account Field Validation Unit Tests")
class AccountValidatorTest {

    // =========================================================================
    // Valid Fixture Values (mirror AccountUpdateServiceTest.createValidDto)
    // =========================================================================

    /** Valid 11-digit non-zero account ID (ACCT-ID PIC 9(11) — CVACT01Y.cpy). */
    private static final String VALID_ACCT_ID = "00000000001";

    /** Valid account active status (ACCT-ACTIVE-STATUS PIC X(01) — Y or N only). */
    private static final String VALID_ACTIVE_STATUS = "Y";

    /** Valid 9-digit SSN (CUST-SSN PIC 9(09) — CVCUS01Y.cpy). */
    private static final String VALID_SSN = "123456789";

    /** Valid FICO credit score within 300-850 range (CUST-FICO-CREDIT-SCORE PIC 9(03)). */
    private static final String VALID_FICO = "750";

    /** Valid 2-character US state code (CUST-ADDR-STATE-CD PIC X(02)). */
    private static final String VALID_STATE = "CA";

    /** Valid 5-digit ZIP code (CUST-ADDR-ZIP PIC X(10)). */
    private static final String VALID_ZIP = "90210";

    /** Valid 10-digit NANPA-compliant phone number (CUST-PHONE-NUM-1 PIC X(15)). */
    private static final String VALID_PHONE = "2125551234";

    /** Valid 10-digit numeric EFT account ID (CUST-EFT-ACCOUNT-ID PIC X(10)). */
    private static final String VALID_EFT = "1234567890";

    // =========================================================================
    // Date-Field Label Constants (passed to DateValidationService by
    // AccountValidator.validateDateField — used with Mockito eq() matcher
    // to force targeted date-validation failures per field)
    // =========================================================================

    /** Label for ACCT-OPEN-DATE (COACTUPC.cbl 1200-EDIT-MAP-INPUTS step 2). */
    private static final String LABEL_OPEN_DATE = "Open Date";

    /** Label for ACCT-EXPIRAION-DATE (COACTUPC.cbl 1200-EDIT-MAP-INPUTS step 4). */
    private static final String LABEL_EXPIRY_DATE = "Expiry Date";

    /** Label for ACCT-REISSUE-DATE (COACTUPC.cbl 1200-EDIT-MAP-INPUTS step 6). */
    private static final String LABEL_REISSUE_DATE = "Reissue Date";

    /** Label for CUST-DOB (COACTUPC.cbl 1200-EDIT-MAP-INPUTS step 11). */
    private static final String LABEL_DOB = "Date of Birth";

    // =========================================================================
    // Mocked Collaborators and Subject Under Test
    // =========================================================================

    /**
     * Mocked date validation service. Replaces COBOL {@code CSUTLDTC.cbl}
     * subprogram + LE {@code CEEDAYS} callable service. Stubbed in
     * {@link #setUp()} to return a valid result by default via
     * {@link #validDateResult()}. Individual tests override via
     * {@code when(...).thenReturn(invalidDateResult(...))} to force
     * targeted date-validation failures.
     */
    @Mock
    private DateValidationService dateValidationService;

    /**
     * Mocked validation lookup service. Replaces COBOL {@code CSLKPCDY.cpy}
     * 88-level condition tables (NANPA area codes, US state codes,
     * state/ZIP prefix combinations). Stubbed in {@link #setUp()} to
     * return {@code true} by default for all three lookup methods.
     * Individual tests override to force targeted lookup failures.
     */
    @Mock
    private ValidationLookupService validationLookupService;

    /**
     * Subject under test. Auto-instantiated by Mockito via
     * {@link AccountValidator#AccountValidator(DateValidationService,
     * ValidationLookupService)} constructor with the two mocked
     * collaborators above.
     */
    @InjectMocks
    private AccountValidator accountValidator;

    // =========================================================================
    // Test Lifecycle
    // =========================================================================

    /**
     * Installs lenient stubs for all mocked collaborator methods so that
     * most tests can use a fully-valid {@code AccountDto} without
     * triggering {@code UnnecessaryStubbingException} for unused stubs.
     * Individual tests override specific stubs to force targeted failures.
     *
     * <p>Five lenient stubs are installed to cover:</p>
     * <ul>
     *   <li>{@link DateValidationService#validateDate(String, String)} —
     *       exercised by steps 2, 4, 6 (Open/Expiry/Reissue Date)</li>
     *   <li>{@link DateValidationService#validateDateOfBirth(String, String)} —
     *       exercised by step 11 (Date of Birth)</li>
     *   <li>{@link ValidationLookupService#isValidStateCode(String)} —
     *       exercised by step 17 (State Code)</li>
     *   <li>{@link ValidationLookupService#isValidAreaCode(String)} —
     *       exercised by steps 21, 22 (Phone 1, Phone 2 area codes)</li>
     *   <li>{@link ValidationLookupService#isValidStateZipPrefix(String, String)} —
     *       exercised by step 25 (cross-field state/ZIP prefix)</li>
     * </ul>
     */
    @BeforeEach
    void setUp() {
        lenient().when(dateValidationService.validateDate(anyString(), anyString()))
                .thenReturn(validDateResult());
        lenient().when(dateValidationService.validateDateOfBirth(anyString(), anyString()))
                .thenReturn(validDateResult());
        lenient().when(validationLookupService.isValidStateCode(anyString()))
                .thenReturn(true);
        lenient().when(validationLookupService.isValidAreaCode(anyString()))
                .thenReturn(true);
        lenient().when(validationLookupService.isValidStateZipPrefix(anyString(), anyString()))
                .thenReturn(true);
    }

    // =========================================================================
    // SECTION 1. validateAccountId(String) — FIRST-FAIL-THROW PATTERN
    //    COBOL Source: COACTUPC.cbl paragraph 1210-EDIT-ACCOUNT (lines 1783+)
    //    Invariant: exactly ONE FieldError per failure (short-circuits on
    //    first violation). Field name is always "acctId".
    // =========================================================================

    @Test
    @DisplayName("validateAccountId: rejects null with 'Account Number must be supplied.'")
    void shouldRejectWhenAccountIdIsNull() {
        assertThatThrownBy(() -> accountValidator.validateAccountId(null))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).fieldName()).isEqualTo("acctId");
                    assertThat(errors.get(0).rejectedValue()).isNull();
                    assertThat(errors.get(0).message())
                            .isEqualTo("Account Number must be supplied.");
                });
    }

    @Test
    @DisplayName("validateAccountId: rejects empty string with 'Account Number must be supplied.'")
    void shouldRejectWhenAccountIdIsEmpty() {
        assertThatThrownBy(() -> accountValidator.validateAccountId(""))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).fieldName()).isEqualTo("acctId");
                    assertThat(errors.get(0).message())
                            .isEqualTo("Account Number must be supplied.");
                });
    }

    @Test
    @DisplayName("validateAccountId: rejects whitespace-only with 'Account Number must be supplied.'")
    void shouldRejectWhenAccountIdIsWhitespaceOnly() {
        assertThatThrownBy(() -> accountValidator.validateAccountId("    "))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).fieldName()).isEqualTo("acctId");
                    assertThat(errors.get(0).message())
                            .isEqualTo("Account Number must be supplied.");
                });
    }

    @Test
    @DisplayName("validateAccountId: rejects non-numeric 11-char value with '11 digit Non-Zero Number'")
    void shouldRejectWhenAccountIdIsNotNumeric() {
        // ACCT-ID PIC 9(11) — must be all digits. 11 chars but embedded alpha.
        String nonNumeric = "abc12345678";
        assertThatThrownBy(() -> accountValidator.validateAccountId(nonNumeric))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).fieldName()).isEqualTo("acctId");
                    assertThat(errors.get(0).rejectedValue()).isEqualTo(nonNumeric);
                    assertThat(errors.get(0).message())
                            .isEqualTo("Account Number if supplied must be a "
                                    + "11 digit Non-Zero Number");
                });
    }

    @Test
    @DisplayName("validateAccountId: rejects short length (10 chars) with '11 digit Non-Zero Number'")
    void shouldRejectWhenAccountIdLengthIsTooShort() {
        String shortId = "1234567890"; // 10 digits, all numeric but wrong length
        assertThatThrownBy(() -> accountValidator.validateAccountId(shortId))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).fieldName()).isEqualTo("acctId");
                    assertThat(errors.get(0).message())
                            .isEqualTo("Account Number if supplied must be a "
                                    + "11 digit Non-Zero Number");
                });
    }

    @Test
    @DisplayName("validateAccountId: rejects long length (12 chars) with '11 digit Non-Zero Number'")
    void shouldRejectWhenAccountIdLengthIsTooLong() {
        String longId = "123456789012"; // 12 digits
        assertThatThrownBy(() -> accountValidator.validateAccountId(longId))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).fieldName()).isEqualTo("acctId");
                    assertThat(errors.get(0).message())
                            .isEqualTo("Account Number if supplied must be a "
                                    + "11 digit Non-Zero Number");
                });
    }

    @Test
    @DisplayName("validateAccountId: rejects all-zeros ('00000000000') with '11 digit Non-Zero Number'")
    void shouldRejectWhenAccountIdIsAllZeros() {
        // AccountConstants.ALL_ZEROS_ACCOUNT_ID — CICS convention for "no account".
        String allZeros = "00000000000"; // 11 zero digits
        assertThatThrownBy(() -> accountValidator.validateAccountId(allZeros))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).fieldName()).isEqualTo("acctId");
                    assertThat(errors.get(0).rejectedValue()).isEqualTo(allZeros);
                    assertThat(errors.get(0).message())
                            .isEqualTo("Account Number if supplied must be a "
                                    + "11 digit Non-Zero Number");
                });
    }

    @Test
    @DisplayName("validateAccountId: accepts valid 11-digit non-zero ID ('00000000001')")
    void shouldAcceptWhenAccountIdIsValid() {
        assertThatCode(() -> accountValidator.validateAccountId(VALID_ACCT_ID))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("validateAccountId: accepts leading/trailing whitespace around valid ID (trimmed before check)")
    void shouldAcceptWhenAccountIdHasSurroundingWhitespace() {
        // isBlankOrNull returns false for "  00000000001  " (has content), then
        // trim() yields "00000000001" which passes all subsequent checks.
        assertThatCode(() -> accountValidator.validateAccountId("  " + VALID_ACCT_ID + "  "))
                .doesNotThrowAnyException();
    }

    // =========================================================================
    // SECTION 2. validateUpdateFields(AccountDto) — ERROR-AGGREGATION PATTERN
    //    COBOL Source: COACTUPC.cbl paragraph 1200-EDIT-MAP-INPUTS
    //    Invariant: collects ALL field errors into a List<FieldError> and
    //    throws a single ValidationException at the end.
    // =========================================================================

    // -------------------------------------------------------------------------
    // SECTION 2a. Active Status (step 1) — COACTUPC.cbl 1220-EDIT-YESNO
    //    Field: acctActiveStatus | Label: "Account Status"
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("2a. Active Status: rejects null with 'Account Status must be supplied.'")
    void shouldRejectWhenActiveStatusIsNull() {
        AccountDto dto = createValidDto();
        dto.setAcctActiveStatus(null);

        assertThatThrownBy(() -> accountValidator.validateUpdateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).fieldName()).isEqualTo("acctActiveStatus");
                    assertThat(errors.get(0).message())
                            .isEqualTo("Account Status must be supplied.");
                });
    }

    @Test
    @DisplayName("2a. Active Status: rejects blank with 'Account Status must be supplied.'")
    void shouldRejectWhenActiveStatusIsBlank() {
        AccountDto dto = createValidDto();
        dto.setAcctActiveStatus("   ");

        assertThatThrownBy(() -> accountValidator.validateUpdateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).fieldName()).isEqualTo("acctActiveStatus");
                    assertThat(errors.get(0).message())
                            .isEqualTo("Account Status must be supplied.");
                });
    }

    @Test
    @DisplayName("2a. Active Status: rejects invalid character 'X' with 'Account Status must be Y or N.'")
    void shouldRejectWhenActiveStatusIsInvalidCharacter() {
        AccountDto dto = createValidDto();
        dto.setAcctActiveStatus("X");

        assertThatThrownBy(() -> accountValidator.validateUpdateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).fieldName()).isEqualTo("acctActiveStatus");
                    assertThat(errors.get(0).rejectedValue()).isEqualTo("X");
                    assertThat(errors.get(0).message())
                            .isEqualTo("Account Status must be Y or N.");
                });
    }

    @Test
    @DisplayName("2a. Active Status: accepts 'Y' (Active) — happy path")
    void shouldAcceptWhenActiveStatusIsY() {
        AccountDto dto = createValidDto();
        dto.setAcctActiveStatus("Y");

        assertThatCode(() -> accountValidator.validateUpdateFields(dto))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("2a. Active Status: accepts 'N' (Inactive) — happy path")
    void shouldAcceptWhenActiveStatusIsN() {
        AccountDto dto = createValidDto();
        dto.setAcctActiveStatus("N");

        assertThatCode(() -> accountValidator.validateUpdateFields(dto))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("2a. Active Status: accepts lowercase 'y' (validator uppercases before compare)")
    void shouldAcceptWhenActiveStatusIsLowercaseY() {
        AccountDto dto = createValidDto();
        dto.setAcctActiveStatus("y");

        assertThatCode(() -> accountValidator.validateUpdateFields(dto))
                .doesNotThrowAnyException();
    }

    // -------------------------------------------------------------------------
    // SECTION 2b. Date Fields (steps 2, 4, 6, 11) — CSUTLDTC.cbl + EDIT-DATE-CCYYMMDD
    //    Delegated to DateValidationService (mocked). AccountValidator converts
    //    LocalDate → "yyyyMMdd" string via DateFormatConstants.CCYYMMDD_FORMATTER
    //    before invoking dateValidationService.validateDate(dateStr, label).
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("2b. Open Date: rejects null with 'Open Date must be supplied.'")
    void shouldRejectWhenOpenDateIsNull() {
        AccountDto dto = createValidDto();
        dto.setAcctOpenDate(null);

        assertThatThrownBy(() -> accountValidator.validateUpdateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).fieldName()).isEqualTo("acctOpenDate");
                    assertThat(errors.get(0).rejectedValue()).isNull();
                    assertThat(errors.get(0).message()).isEqualTo("Open Date must be supplied.");
                });
    }

    @Test
    @DisplayName("2b. Open Date: rejects when DateValidationService reports invalid")
    void shouldRejectWhenOpenDateIsInvalid() {
        AccountDto dto = createValidDto();
        // Override the lenient stub for the "Open Date" label specifically
        when(dateValidationService.validateDate(anyString(), eq(LABEL_OPEN_DATE)))
                .thenReturn(invalidDateResult("Year is not valid"));

        assertThatThrownBy(() -> accountValidator.validateUpdateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).fieldName()).isEqualTo("acctOpenDate");
                    // rejectedValue is the formatted CCYYMMDD string, not the LocalDate
                    assertThat(errors.get(0).rejectedValue()).isEqualTo("20200115");
                    assertThat(errors.get(0).message()).isEqualTo("Year is not valid");
                });
    }

    @Test
    @DisplayName("2b. Expiry Date: rejects null with 'Expiry Date must be supplied.'")
    void shouldRejectWhenExpiryDateIsNull() {
        AccountDto dto = createValidDto();
        dto.setAcctExpDate(null);

        assertThatThrownBy(() -> accountValidator.validateUpdateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).fieldName()).isEqualTo("acctExpDate");
                    assertThat(errors.get(0).message())
                            .isEqualTo("Expiry Date must be supplied.");
                });
    }

    @Test
    @DisplayName("2b. Expiry Date: rejects when DateValidationService reports invalid")
    void shouldRejectWhenExpiryDateIsInvalid() {
        AccountDto dto = createValidDto();
        when(dateValidationService.validateDate(anyString(), eq(LABEL_EXPIRY_DATE)))
                .thenReturn(invalidDateResult("Month is not valid"));

        assertThatThrownBy(() -> accountValidator.validateUpdateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).fieldName()).isEqualTo("acctExpDate");
                    assertThat(errors.get(0).message()).isEqualTo("Month is not valid");
                });
    }

    @Test
    @DisplayName("2b. Reissue Date: rejects null with 'Reissue Date must be supplied.'")
    void shouldRejectWhenReissueDateIsNull() {
        AccountDto dto = createValidDto();
        dto.setAcctReissueDate(null);

        assertThatThrownBy(() -> accountValidator.validateUpdateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).fieldName()).isEqualTo("acctReissueDate");
                    assertThat(errors.get(0).message())
                            .isEqualTo("Reissue Date must be supplied.");
                });
    }

    @Test
    @DisplayName("2b. Reissue Date: rejects when DateValidationService reports invalid")
    void shouldRejectWhenReissueDateIsInvalid() {
        AccountDto dto = createValidDto();
        when(dateValidationService.validateDate(anyString(), eq(LABEL_REISSUE_DATE)))
                .thenReturn(invalidDateResult("Day is not valid"));

        assertThatThrownBy(() -> accountValidator.validateUpdateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).fieldName()).isEqualTo("acctReissueDate");
                    assertThat(errors.get(0).message()).isEqualTo("Day is not valid");
                });
    }

    @Test
    @DisplayName("2b. Date of Birth: rejects null with 'Date of Birth must be supplied.'")
    void shouldRejectWhenDateOfBirthIsNull() {
        AccountDto dto = createValidDto();
        dto.setCustDob(null);

        assertThatThrownBy(() -> accountValidator.validateUpdateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).fieldName()).isEqualTo("custDob");
                    assertThat(errors.get(0).message())
                            .isEqualTo("Date of Birth must be supplied.");
                });
    }

    @Test
    @DisplayName("2b. Date of Birth: rejects when DateValidationService reports invalid (future DOB)")
    void shouldRejectWhenDateOfBirthIsInvalid() {
        AccountDto dto = createValidDto();
        // validateDateOfBirthField (step 11) calls validateDateOfBirth() — NOT validateDate()
        when(dateValidationService.validateDateOfBirth(anyString(), eq(LABEL_DOB)))
                .thenReturn(invalidDateResult("Date of birth cannot be in the future"));

        assertThatThrownBy(() -> accountValidator.validateUpdateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).fieldName()).isEqualTo("custDob");
                    assertThat(errors.get(0).message())
                            .isEqualTo("Date of birth cannot be in the future");
                });
    }



    // -------------------------------------------------------------------------
    // SECTION 2c. Monetary Fields (steps 3, 5, 7, 8, 9) — COACTUPC.cbl 1250-EDIT-SIGNED-9V2
    //    Fields: acctCreditLimit, acctCashCreditLimit, acctCurrBal,
    //            acctCurrCycCredit, acctCurrCycDebit (all BigDecimal)
    //    Invariant: BigDecimal type guarantees numeric format — only null
    //    check is needed (PACKED-DECIMAL semantics preserved per Decision D-001).
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("2c. Credit Limit: rejects null with 'Credit Limit must be supplied.'")
    void shouldRejectWhenCreditLimitIsNull() {
        AccountDto dto = createValidDto();
        dto.setAcctCreditLimit(null);

        assertThatThrownBy(() -> accountValidator.validateUpdateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).fieldName()).isEqualTo("acctCreditLimit");
                    assertThat(errors.get(0).rejectedValue()).isNull();
                    assertThat(errors.get(0).message())
                            .isEqualTo("Credit Limit must be supplied.");
                });
    }

    @Test
    @DisplayName("2c. Cash Credit Limit: rejects null with 'Cash Credit Limit must be supplied.'")
    void shouldRejectWhenCashCreditLimitIsNull() {
        AccountDto dto = createValidDto();
        dto.setAcctCashCreditLimit(null);

        assertThatThrownBy(() -> accountValidator.validateUpdateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).fieldName()).isEqualTo("acctCashCreditLimit");
                    assertThat(errors.get(0).message())
                            .isEqualTo("Cash Credit Limit must be supplied.");
                });
    }

    @Test
    @DisplayName("2c. Current Balance: rejects null with 'Current Balance must be supplied.'")
    void shouldRejectWhenCurrentBalanceIsNull() {
        AccountDto dto = createValidDto();
        dto.setAcctCurrBal(null);

        assertThatThrownBy(() -> accountValidator.validateUpdateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).fieldName()).isEqualTo("acctCurrBal");
                    assertThat(errors.get(0).message())
                            .isEqualTo("Current Balance must be supplied.");
                });
    }

    @Test
    @DisplayName("2c. Current Cycle Credit: rejects null with 'Current Cycle Credit must be supplied.'")
    void shouldRejectWhenCurrentCycleCreditIsNull() {
        AccountDto dto = createValidDto();
        dto.setAcctCurrCycCredit(null);

        assertThatThrownBy(() -> accountValidator.validateUpdateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).fieldName()).isEqualTo("acctCurrCycCredit");
                    assertThat(errors.get(0).message())
                            .isEqualTo("Current Cycle Credit must be supplied.");
                });
    }

    @Test
    @DisplayName("2c. Current Cycle Debit: rejects null with 'Current Cycle Debit must be supplied.'")
    void shouldRejectWhenCurrentCycleDebitIsNull() {
        AccountDto dto = createValidDto();
        dto.setAcctCurrCycDebit(null);

        assertThatThrownBy(() -> accountValidator.validateUpdateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).fieldName()).isEqualTo("acctCurrCycDebit");
                    assertThat(errors.get(0).message())
                            .isEqualTo("Current Cycle Debit must be supplied.");
                });
    }

    @Test
    @DisplayName("2c. Credit Limit: preserves BigDecimal precision (PACKED-DECIMAL semantics)")
    void shouldPreserveBigDecimalPrecisionForCreditLimit() {
        AccountDto dto = createValidDto();
        // BigDecimal value with 2 decimal places — matches COBOL S9(10)V99
        BigDecimal preciseLimit = new BigDecimal("12345.67");
        dto.setAcctCreditLimit(preciseLimit);

        // Validator accepts any non-null BigDecimal; precision is preserved.
        assertThatCode(() -> accountValidator.validateUpdateFields(dto))
                .doesNotThrowAnyException();
        // Verify the DTO value remains unmodified (no lossy conversion)
        assertThat(dto.getAcctCreditLimit()).isEqualByComparingTo(preciseLimit);
    }

    @Test
    @DisplayName("2c. Current Balance: preserves BigDecimal.ZERO without throwing")
    void shouldPreserveBigDecimalZeroForCurrentBalance() {
        AccountDto dto = createValidDto();
        dto.setAcctCurrBal(BigDecimal.ZERO);

        assertThatCode(() -> accountValidator.validateUpdateFields(dto))
                .doesNotThrowAnyException();
        assertThat(dto.getAcctCurrBal()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("2c. Cash Credit Limit: preserves large BigDecimal ('99999999.99' — max COMP-3 range)")
    void shouldPreserveLargeBigDecimalForCashCreditLimit() {
        AccountDto dto = createValidDto();
        BigDecimal largeLimit = new BigDecimal("99999999.99");
        dto.setAcctCashCreditLimit(largeLimit);

        assertThatCode(() -> accountValidator.validateUpdateFields(dto))
                .doesNotThrowAnyException();
        assertThat(dto.getAcctCashCreditLimit()).isEqualByComparingTo(largeLimit);
    }

    // -------------------------------------------------------------------------
    // SECTION 2d. SSN Validation (step 10) — COACTUPC.cbl 1265-EDIT-US-SSN
    //    Field: custSsn | PIC 9(09) — 9-digit SSN with 3-part rules
    //    Part 1 (digits 1-3): not 000, not 666, not 900-999
    //    Part 2 (digits 4-5): not 00
    //    Part 3 (digits 6-9): not 0000
    //    Note: all 3 part-checks can accumulate in ONE call (no early return).
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("2d. SSN: rejects null with 'SSN must be supplied.'")
    void shouldRejectWhenSsnIsNull() {
        AccountDto dto = createValidDto();
        dto.setCustSsn(null);

        assertThatThrownBy(() -> accountValidator.validateUpdateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).fieldName()).isEqualTo("custSsn");
                    assertThat(errors.get(0).message()).isEqualTo("SSN must be supplied.");
                });
    }

    @Test
    @DisplayName("2d. SSN: rejects wrong length (8 digits) with 'SSN must be exactly 9 digits.'")
    void shouldRejectWhenSsnLengthIsNotNine() {
        AccountDto dto = createValidDto();
        dto.setCustSsn("12345678"); // 8 digits

        assertThatThrownBy(() -> accountValidator.validateUpdateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).fieldName()).isEqualTo("custSsn");
                    assertThat(errors.get(0).message())
                            .isEqualTo("SSN must be exactly 9 digits.");
                });
    }

    @Test
    @DisplayName("2d. SSN first 3 == '000': rejects with 'First 3 chars: should not be 000, 666, or between 900 and 999'")
    void shouldRejectWhenSsnFirstThreeIs000() {
        AccountDto dto = createValidDto();
        // First-3 = 000 (invalid); middle & last keep valid to isolate the first-3 error.
        dto.setCustSsn("000456789");

        assertThatThrownBy(() -> accountValidator.validateUpdateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).fieldName()).isEqualTo("custSsn");
                    assertThat(errors.get(0).message())
                            .isEqualTo("SSN: First 3 chars: should not be "
                                    + "000, 666, or between 900 and 999");
                });
    }

    @Test
    @DisplayName("2d. SSN first 3 == '666': rejects with 'First 3 chars: should not be 000, 666, or between 900 and 999'")
    void shouldRejectWhenSsnFirstThreeIs666() {
        AccountDto dto = createValidDto();
        dto.setCustSsn("666456789");

        assertThatThrownBy(() -> accountValidator.validateUpdateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).fieldName()).isEqualTo("custSsn");
                    assertThat(errors.get(0).message())
                            .isEqualTo("SSN: First 3 chars: should not be "
                                    + "000, 666, or between 900 and 999");
                });
    }

    @Test
    @DisplayName("2d. SSN first 3 in 900-999: rejects with 'First 3 chars: should not be 000, 666, or between 900 and 999'")
    void shouldRejectWhenSsnFirstThreeIs900Range() {
        AccountDto dto = createValidDto();
        dto.setCustSsn("900123456");

        assertThatThrownBy(() -> accountValidator.validateUpdateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).fieldName()).isEqualTo("custSsn");
                    assertThat(errors.get(0).message())
                            .isEqualTo("SSN: First 3 chars: should not be "
                                    + "000, 666, or between 900 and 999");
                });
    }

    @Test
    @DisplayName("2d. SSN middle 2 == '00': rejects with 'SSN 4th & 5th chars must not be zero.'")
    void shouldRejectWhenSsnMiddleTwoAre00() {
        AccountDto dto = createValidDto();
        // Middle-2 = 00 (invalid); first-3 and last-4 remain valid.
        dto.setCustSsn("123001234");

        assertThatThrownBy(() -> accountValidator.validateUpdateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).fieldName()).isEqualTo("custSsn");
                    assertThat(errors.get(0).message())
                            .isEqualTo("SSN 4th & 5th chars must not be zero.");
                });
    }

    @Test
    @DisplayName("2d. SSN last 4 == '0000': rejects with 'SSN Last 4 chars must not be zero.'")
    void shouldRejectWhenSsnLastFourAre0000() {
        AccountDto dto = createValidDto();
        // Last-4 = 0000 (invalid); first-3 and middle-2 remain valid.
        dto.setCustSsn("123450000");

        assertThatThrownBy(() -> accountValidator.validateUpdateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).fieldName()).isEqualTo("custSsn");
                    assertThat(errors.get(0).message())
                            .isEqualTo("SSN Last 4 chars must not be zero.");
                });
    }

    @Test
    @DisplayName("2d. SSN '000000000' (all parts invalid): aggregates all 3 SSN errors in one invocation")
    void shouldAggregateAllThreeSsnPartErrorsForAllZeros() {
        AccountDto dto = createValidDto();
        // All 3 SSN parts violate rules — validator continues after each part
        // (no early return between part-1, part-2, part-3 checks).
        dto.setCustSsn("000000000");

        assertThatThrownBy(() -> accountValidator.validateUpdateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors).hasSize(3);
                    assertThat(errors).allMatch(e -> "custSsn".equals(e.fieldName()));
                    assertThat(errors).extracting(FieldError::message)
                            .containsExactly(
                                    "SSN: First 3 chars: should not be "
                                            + "000, 666, or between 900 and 999",
                                    "SSN 4th & 5th chars must not be zero.",
                                    "SSN Last 4 chars must not be zero.");
                });
    }

    @Test
    @DisplayName("2d. SSN '123456789' (valid 3-part): accepts without error")
    void shouldAcceptValidSsn() {
        AccountDto dto = createValidDto();
        dto.setCustSsn("123456789");

        assertThatCode(() -> accountValidator.validateUpdateFields(dto))
                .doesNotThrowAnyException();
    }



    // -------------------------------------------------------------------------
    // SECTION 2e. FICO Score Validation (step 12) — COACTUPC.cbl 1275-EDIT-FICO-SCORE
    //    Field: custFicoScore | Label (hardcoded): "FICO Score"
    //    Range: 300 (FICO_MIN) to 850 (FICO_MAX) inclusive
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("2e. FICO: rejects null with 'FICO Score must be supplied.'")
    void shouldRejectWhenFicoScoreIsNull() {
        AccountDto dto = createValidDto();
        dto.setCustFicoScore(null);

        assertThatThrownBy(() -> accountValidator.validateUpdateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).fieldName()).isEqualTo("custFicoScore");
                    assertThat(errors.get(0).message())
                            .isEqualTo("FICO Score must be supplied.");
                });
    }

    @Test
    @DisplayName("2e. FICO: rejects blank with 'FICO Score must be supplied.'")
    void shouldRejectWhenFicoScoreIsBlank() {
        AccountDto dto = createValidDto();
        dto.setCustFicoScore("   ");

        assertThatThrownBy(() -> accountValidator.validateUpdateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).fieldName()).isEqualTo("custFicoScore");
                    assertThat(errors.get(0).message())
                            .isEqualTo("FICO Score must be supplied.");
                });
    }

    @Test
    @DisplayName("2e. FICO: rejects non-numeric ('abc') with 'FICO Score must be numeric.'")
    void shouldRejectWhenFicoScoreIsNotNumeric() {
        AccountDto dto = createValidDto();
        dto.setCustFicoScore("abc");

        assertThatThrownBy(() -> accountValidator.validateUpdateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).fieldName()).isEqualTo("custFicoScore");
                    assertThat(errors.get(0).rejectedValue()).isEqualTo("abc");
                    assertThat(errors.get(0).message())
                            .isEqualTo("FICO Score must be numeric.");
                });
    }

    @Test
    @DisplayName("2e. FICO: rejects '299' (below 300) with 'FICO Score: should be between 300 and 850'")
    void shouldRejectWhenFicoScoreBelow300() {
        AccountDto dto = createValidDto();
        dto.setCustFicoScore("299");

        assertThatThrownBy(() -> accountValidator.validateUpdateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).fieldName()).isEqualTo("custFicoScore");
                    assertThat(errors.get(0).rejectedValue()).isEqualTo("299");
                    // Note: NO trailing period on this message (per AccountValidator.java:578)
                    assertThat(errors.get(0).message())
                            .isEqualTo("FICO Score: should be between 300 and 850");
                });
    }

    @Test
    @DisplayName("2e. FICO: rejects '851' (above 850) with 'FICO Score: should be between 300 and 850'")
    void shouldRejectWhenFicoScoreAbove850() {
        AccountDto dto = createValidDto();
        dto.setCustFicoScore("851");

        assertThatThrownBy(() -> accountValidator.validateUpdateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).fieldName()).isEqualTo("custFicoScore");
                    assertThat(errors.get(0).message())
                            .isEqualTo("FICO Score: should be between 300 and 850");
                });
    }

    @Test
    @DisplayName("2e. FICO: accepts '300' (lower boundary inclusive)")
    void shouldAcceptWhenFicoScoreIs300() {
        AccountDto dto = createValidDto();
        dto.setCustFicoScore("300");

        assertThatCode(() -> accountValidator.validateUpdateFields(dto))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("2e. FICO: accepts '850' (upper boundary inclusive)")
    void shouldAcceptWhenFicoScoreIs850() {
        AccountDto dto = createValidDto();
        dto.setCustFicoScore("850");

        assertThatCode(() -> accountValidator.validateUpdateFields(dto))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("2e. FICO: accepts mid-range valid score ('720')")
    void shouldAcceptValidFicoScore() {
        AccountDto dto = createValidDto();
        dto.setCustFicoScore("720");

        assertThatCode(() -> accountValidator.validateUpdateFields(dto))
                .doesNotThrowAnyException();
    }

    // -------------------------------------------------------------------------
    // SECTION 2f. Name Field Validation (steps 13, 14, 15) —
    //    COACTUPC.cbl 1225-EDIT-ALPHA-REQD (custFname, custLname)
    //    COACTUPC.cbl 1235-EDIT-ALPHA-OPT  (custMname)
    //    Regex: ^[a-zA-Z ]+$ (alpha + space, NOT digits)
    //    Max length: 25 chars each (CUST-FIRST/MIDDLE/LAST-NAME PIC X(25))
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("2f. First Name: rejects null with 'First Name must be supplied.'")
    void shouldRejectWhenFirstNameIsNull() {
        AccountDto dto = createValidDto();
        dto.setCustFname(null);

        assertThatThrownBy(() -> accountValidator.validateUpdateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).fieldName()).isEqualTo("custFname");
                    assertThat(errors.get(0).message())
                            .isEqualTo("First Name must be supplied.");
                });
    }

    @Test
    @DisplayName("2f. First Name: rejects blank with 'First Name must be supplied.'")
    void shouldRejectWhenFirstNameIsBlank() {
        AccountDto dto = createValidDto();
        dto.setCustFname("   ");

        assertThatThrownBy(() -> accountValidator.validateUpdateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).fieldName()).isEqualTo("custFname");
                    assertThat(errors.get(0).message())
                            .isEqualTo("First Name must be supplied.");
                });
    }

    @Test
    @DisplayName("2f. First Name: rejects digits ('John123') with 'First Name can have alphabets only.'")
    void shouldRejectWhenFirstNameHasDigits() {
        AccountDto dto = createValidDto();
        dto.setCustFname("John123");

        assertThatThrownBy(() -> accountValidator.validateUpdateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).fieldName()).isEqualTo("custFname");
                    assertThat(errors.get(0).message())
                            .isEqualTo("First Name can have alphabets only.");
                });
    }

    @Test
    @DisplayName("2f. First Name: rejects length > 25 with 'First Name must not exceed 25 characters.'")
    void shouldRejectWhenFirstNameExceedsMaxLength() {
        AccountDto dto = createValidDto();
        // 26 chars — exceeds max 25
        dto.setCustFname("AAAAAAAAAAAAAAAAAAAAAAAAAA");

        assertThatThrownBy(() -> accountValidator.validateUpdateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).fieldName()).isEqualTo("custFname");
                    assertThat(errors.get(0).message())
                            .isEqualTo("First Name must not exceed 25 characters.");
                });
    }

    @Test
    @DisplayName("2f. First Name: accepts name with embedded space ('Mary Ann')")
    void shouldAcceptFirstNameWithSpaces() {
        AccountDto dto = createValidDto();
        dto.setCustFname("Mary Ann");

        assertThatCode(() -> accountValidator.validateUpdateFields(dto))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("2f. Middle Name (optional): accepts null without error")
    void shouldAcceptWhenMiddleNameIsNull() {
        AccountDto dto = createValidDto();
        dto.setCustMname(null);

        assertThatCode(() -> accountValidator.validateUpdateFields(dto))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("2f. Middle Name (optional): accepts empty string without error")
    void shouldAcceptWhenMiddleNameIsEmpty() {
        AccountDto dto = createValidDto();
        dto.setCustMname("");

        assertThatCode(() -> accountValidator.validateUpdateFields(dto))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("2f. Middle Name (optional): rejects digits when non-empty")
    void shouldRejectWhenMiddleNameHasDigits() {
        AccountDto dto = createValidDto();
        dto.setCustMname("Mike5");

        assertThatThrownBy(() -> accountValidator.validateUpdateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).fieldName()).isEqualTo("custMname");
                    assertThat(errors.get(0).message())
                            .isEqualTo("Middle Name can have alphabets only.");
                });
    }

    @Test
    @DisplayName("2f. Middle Name (optional): rejects length > 25")
    void shouldRejectWhenMiddleNameExceedsMaxLength() {
        AccountDto dto = createValidDto();
        dto.setCustMname("AAAAAAAAAAAAAAAAAAAAAAAAAA"); // 26 chars

        assertThatThrownBy(() -> accountValidator.validateUpdateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).fieldName()).isEqualTo("custMname");
                    assertThat(errors.get(0).message())
                            .isEqualTo("Middle Name must not exceed 25 characters.");
                });
    }

    @Test
    @DisplayName("2f. Last Name: rejects null with 'Last Name must be supplied.'")
    void shouldRejectWhenLastNameIsNull() {
        AccountDto dto = createValidDto();
        dto.setCustLname(null);

        assertThatThrownBy(() -> accountValidator.validateUpdateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).fieldName()).isEqualTo("custLname");
                    assertThat(errors.get(0).message())
                            .isEqualTo("Last Name must be supplied.");
                });
    }

    @Test
    @DisplayName("2f. Last Name: rejects digits ('Smith7') with 'Last Name can have alphabets only.'")
    void shouldRejectWhenLastNameHasDigits() {
        AccountDto dto = createValidDto();
        dto.setCustLname("Smith7");

        assertThatThrownBy(() -> accountValidator.validateUpdateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).fieldName()).isEqualTo("custLname");
                    assertThat(errors.get(0).message())
                            .isEqualTo("Last Name can have alphabets only.");
                });
    }



    // -------------------------------------------------------------------------
    // SECTION 2g. Address Line 1 (step 16) — COACTUPC.cbl 1215-EDIT-MANDATORY
    //    Field: custAddr1 | Label: "Address Line 1"
    //    Mandatory-only check (no regex, no length constraint at this level).
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("2g. Address Line 1: rejects null with 'Address Line 1 must be supplied.'")
    void shouldRejectWhenAddressLine1IsNull() {
        AccountDto dto = createValidDto();
        dto.setCustAddr1(null);

        assertThatThrownBy(() -> accountValidator.validateUpdateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).fieldName()).isEqualTo("custAddr1");
                    assertThat(errors.get(0).message())
                            .isEqualTo("Address Line 1 must be supplied.");
                });
    }

    @Test
    @DisplayName("2g. Address Line 1: rejects blank with 'Address Line 1 must be supplied.'")
    void shouldRejectWhenAddressLine1IsBlank() {
        AccountDto dto = createValidDto();
        dto.setCustAddr1("   ");

        assertThatThrownBy(() -> accountValidator.validateUpdateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).fieldName()).isEqualTo("custAddr1");
                    assertThat(errors.get(0).message())
                            .isEqualTo("Address Line 1 must be supplied.");
                });
    }

    @Test
    @DisplayName("2g. Address Line 1: accepts valid address with digits and special chars (mandatory-only)")
    void shouldAcceptAddressLine1WithDigitsAndSpecialChars() {
        AccountDto dto = createValidDto();
        dto.setCustAddr1("1234 Elm St. #5");

        assertThatCode(() -> accountValidator.validateUpdateFields(dto))
                .doesNotThrowAnyException();
    }

    // -------------------------------------------------------------------------
    // SECTION 2h. State Field (step 17) — COACTUPC.cbl 1225-EDIT-ALPHA-REQD
    //                                     + 1270-EDIT-US-STATE-CD
    //    Field: custState (fieldKey "custState", hardcoded label "State")
    //    Regex: ^[a-zA-Z]+$  (NO SPACES — different from other alpha-required fields)
    //    Lookup: ValidationLookupService.isValidStateCode(upper) against CSLKPCDY
    //    RETURNS boolean to enable cross-field step 25 state+zip prefix.
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("2h. State: rejects null with 'State must be supplied.'")
    void shouldRejectWhenStateIsNull() {
        AccountDto dto = createValidDto();
        dto.setCustState(null);

        assertThatThrownBy(() -> accountValidator.validateUpdateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).fieldName()).isEqualTo("custState");
                    assertThat(errors.get(0).message())
                            .isEqualTo("State must be supplied.");
                });
    }

    @Test
    @DisplayName("2h. State: rejects blank with 'State must be supplied.'")
    void shouldRejectWhenStateIsBlank() {
        AccountDto dto = createValidDto();
        dto.setCustState("   ");

        assertThatThrownBy(() -> accountValidator.validateUpdateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).fieldName()).isEqualTo("custState");
                    assertThat(errors.get(0).message())
                            .isEqualTo("State must be supplied.");
                });
    }

    @Test
    @DisplayName("2h. State: rejects digits ('C1') with 'State can have alphabets only.'")
    void shouldRejectWhenStateHasDigits() {
        AccountDto dto = createValidDto();
        dto.setCustState("C1");

        assertThatThrownBy(() -> accountValidator.validateUpdateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).fieldName()).isEqualTo("custState");
                    assertThat(errors.get(0).message())
                            .isEqualTo("State can have alphabets only.");
                });
    }

    @Test
    @DisplayName("2h. State: rejects embedded space ('C A') — state regex disallows spaces")
    void shouldRejectWhenStateHasSpace() {
        AccountDto dto = createValidDto();
        // The state regex ^[a-zA-Z]+$ DOES NOT allow spaces
        // (distinct from other alpha fields like name/city which use ^[a-zA-Z ]+$).
        dto.setCustState("C A");

        assertThatThrownBy(() -> accountValidator.validateUpdateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).fieldName()).isEqualTo("custState");
                    assertThat(errors.get(0).message())
                            .isEqualTo("State can have alphabets only.");
                });
    }

    @Test
    @DisplayName("2h. State: rejects non-existent state code ('XX') with 'State: is not a valid state code'")
    void shouldRejectWhenStateCodeIsNotValid() {
        AccountDto dto = createValidDto();
        dto.setCustState("XX");
        // Override the lenient 'true' default so XX reports as invalid
        when(validationLookupService.isValidStateCode("XX")).thenReturn(false);

        assertThatThrownBy(() -> accountValidator.validateUpdateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).fieldName()).isEqualTo("custState");
                    assertThat(errors.get(0).rejectedValue()).isEqualTo("XX");
                    // Note: NO trailing period per AccountValidator.java:699
                    assertThat(errors.get(0).message())
                            .isEqualTo("State: is not a valid state code");
                });
    }

    @Test
    @DisplayName("2h. State: accepts valid state code (mixed case 'ca' uppercased for lookup)")
    void shouldAcceptValidStateCode() {
        AccountDto dto = createValidDto();
        dto.setCustState("CA");

        assertThatCode(() -> accountValidator.validateUpdateFields(dto))
                .doesNotThrowAnyException();
    }

    // -------------------------------------------------------------------------
    // SECTION 2i. ZIP Code Field (step 18) — COACTUPC.cbl ZIP validation
    //    Field: custZip (fieldKey "custZip", hardcoded label "ZIP Code")
    //    Regex: \d{5}(-\d{4})?  (5-digit OR ZIP+4 format)
    //    Base-5 must not be all zeros.
    //    RETURNS boolean to enable cross-field step 25 state+zip prefix.
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("2i. ZIP: rejects null with 'ZIP Code must be supplied.'")
    void shouldRejectWhenZipIsNull() {
        AccountDto dto = createValidDto();
        dto.setCustZip(null);

        assertThatThrownBy(() -> accountValidator.validateUpdateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).fieldName()).isEqualTo("custZip");
                    assertThat(errors.get(0).message())
                            .isEqualTo("ZIP Code must be supplied.");
                });
    }

    @Test
    @DisplayName("2i. ZIP: rejects blank with 'ZIP Code must be supplied.'")
    void shouldRejectWhenZipIsBlank() {
        AccountDto dto = createValidDto();
        dto.setCustZip("   ");

        assertThatThrownBy(() -> accountValidator.validateUpdateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).fieldName()).isEqualTo("custZip");
                    assertThat(errors.get(0).message())
                            .isEqualTo("ZIP Code must be supplied.");
                });
    }

    @Test
    @DisplayName("2i. ZIP: rejects short '1234' (4 digits) with ZIP+4 format message")
    void shouldRejectWhenZipIsNot5Digits() {
        AccountDto dto = createValidDto();
        dto.setCustZip("1234");

        assertThatThrownBy(() -> accountValidator.validateUpdateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).fieldName()).isEqualTo("custZip");
                    assertThat(errors.get(0).message())
                            .isEqualTo("ZIP Code must be 5 digits or ZIP+4 format (e.g., 12345 or 12345-6789).");
                });
    }

    @Test
    @DisplayName("2i. ZIP: rejects malformed ZIP+4 ('12345-ABC') with ZIP+4 format message")
    void shouldRejectInvalidZipPlus4() {
        AccountDto dto = createValidDto();
        dto.setCustZip("12345-ABC");

        assertThatThrownBy(() -> accountValidator.validateUpdateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).fieldName()).isEqualTo("custZip");
                    assertThat(errors.get(0).message())
                            .isEqualTo("ZIP Code must be 5 digits or ZIP+4 format (e.g., 12345 or 12345-6789).");
                });
    }

    @Test
    @DisplayName("2i. ZIP: rejects all-zeros base ('00000') with 'ZIP Code must not be zero.'")
    void shouldRejectWhenZipIsAllZeros() {
        AccountDto dto = createValidDto();
        dto.setCustZip("00000");

        assertThatThrownBy(() -> accountValidator.validateUpdateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).fieldName()).isEqualTo("custZip");
                    assertThat(errors.get(0).message())
                            .isEqualTo("ZIP Code must not be zero.");
                });
    }

    @Test
    @DisplayName("2i. ZIP: accepts 5-digit ZIP ('90210')")
    void shouldAcceptZip5DigitFormat() {
        AccountDto dto = createValidDto();
        dto.setCustZip("90210");

        assertThatCode(() -> accountValidator.validateUpdateFields(dto))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("2i. ZIP: accepts ZIP+4 format ('48251-1698')")
    void shouldAcceptZipPlus4Format() {
        AccountDto dto = createValidDto();
        dto.setCustZip("48251-1698");

        assertThatCode(() -> accountValidator.validateUpdateFields(dto))
                .doesNotThrowAnyException();
    }

    // -------------------------------------------------------------------------
    // SECTION 2j. City Field (step 19) — COACTUPC.cbl 1225-EDIT-ALPHA-REQD
    //    Field: custCity | Label: "City" | Max length: 50
    //    Regex: ^[a-zA-Z ]+$  (alpha + space, NO digits)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("2j. City: rejects null with 'City must be supplied.'")
    void shouldRejectWhenCityIsNull() {
        AccountDto dto = createValidDto();
        dto.setCustCity(null);

        assertThatThrownBy(() -> accountValidator.validateUpdateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).fieldName()).isEqualTo("custCity");
                    assertThat(errors.get(0).message())
                            .isEqualTo("City must be supplied.");
                });
    }

    @Test
    @DisplayName("2j. City: rejects blank with 'City must be supplied.'")
    void shouldRejectWhenCityIsBlank() {
        AccountDto dto = createValidDto();
        dto.setCustCity("   ");

        assertThatThrownBy(() -> accountValidator.validateUpdateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).fieldName()).isEqualTo("custCity");
                    assertThat(errors.get(0).message())
                            .isEqualTo("City must be supplied.");
                });
    }

    @Test
    @DisplayName("2j. City: rejects digits ('L0s Angeles') with 'City can have alphabets only.'")
    void shouldRejectWhenCityHasDigits() {
        AccountDto dto = createValidDto();
        dto.setCustCity("L0s Angeles");

        assertThatThrownBy(() -> accountValidator.validateUpdateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).fieldName()).isEqualTo("custCity");
                    assertThat(errors.get(0).message())
                            .isEqualTo("City can have alphabets only.");
                });
    }

    @Test
    @DisplayName("2j. City: rejects length > 50 with 'City must not exceed 50 characters.'")
    void shouldRejectWhenCityExceedsMaxLength() {
        AccountDto dto = createValidDto();
        // 51 chars
        dto.setCustCity("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");

        assertThatThrownBy(() -> accountValidator.validateUpdateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).fieldName()).isEqualTo("custCity");
                    assertThat(errors.get(0).message())
                            .isEqualTo("City must not exceed 50 characters.");
                });
    }

    @Test
    @DisplayName("2j. City: accepts city with spaces ('Los Angeles')")
    void shouldAcceptCityWithSpaces() {
        AccountDto dto = createValidDto();
        dto.setCustCity("Los Angeles");

        assertThatCode(() -> accountValidator.validateUpdateFields(dto))
                .doesNotThrowAnyException();
    }

    // -------------------------------------------------------------------------
    // SECTION 2k. Country Field (step 20) — COACTUPC.cbl 1225-EDIT-ALPHA-REQD
    //    Field: custCountry | Label: "Country" | Max length: 3
    //    Regex: ^[a-zA-Z ]+$
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("2k. Country: rejects null with 'Country must be supplied.'")
    void shouldRejectWhenCountryIsNull() {
        AccountDto dto = createValidDto();
        dto.setCustCountry(null);

        assertThatThrownBy(() -> accountValidator.validateUpdateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).fieldName()).isEqualTo("custCountry");
                    assertThat(errors.get(0).message())
                            .isEqualTo("Country must be supplied.");
                });
    }

    @Test
    @DisplayName("2k. Country: rejects blank with 'Country must be supplied.'")
    void shouldRejectWhenCountryIsBlank() {
        AccountDto dto = createValidDto();
        dto.setCustCountry("   ");

        assertThatThrownBy(() -> accountValidator.validateUpdateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).fieldName()).isEqualTo("custCountry");
                    assertThat(errors.get(0).message())
                            .isEqualTo("Country must be supplied.");
                });
    }

    @Test
    @DisplayName("2k. Country: rejects digits ('U5A') with 'Country can have alphabets only.'")
    void shouldRejectWhenCountryHasDigits() {
        AccountDto dto = createValidDto();
        dto.setCustCountry("U5A");

        assertThatThrownBy(() -> accountValidator.validateUpdateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).fieldName()).isEqualTo("custCountry");
                    assertThat(errors.get(0).message())
                            .isEqualTo("Country can have alphabets only.");
                });
    }

    @Test
    @DisplayName("2k. Country: rejects length > 3 with 'Country must not exceed 3 characters.'")
    void shouldRejectWhenCountryExceedsMaxLength() {
        AccountDto dto = createValidDto();
        dto.setCustCountry("USAX"); // 4 chars

        assertThatThrownBy(() -> accountValidator.validateUpdateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).fieldName()).isEqualTo("custCountry");
                    assertThat(errors.get(0).message())
                            .isEqualTo("Country must not exceed 3 characters.");
                });
    }

    @Test
    @DisplayName("2k. Country: accepts short country code ('US')")
    void shouldAcceptCountryCodeUS() {
        AccountDto dto = createValidDto();
        dto.setCustCountry("US");

        assertThatCode(() -> accountValidator.validateUpdateFields(dto))
                .doesNotThrowAnyException();
    }



    // -------------------------------------------------------------------------
    // SECTION 2l. Phone Number Validation (steps 21, 22) —
    //             COACTUPC.cbl 1260-EDIT-US-PHONE-NUM
    //    Fields: custPhone1 ("Phone 1"), custPhone2 ("Phone 2")
    //    Format: 10 digits (area 3 + prefix 3 + line 4), with formatting stripped
    //    OPTIONAL — blank/null returns silently
    //    Area code: non-zero AND ValidationLookupService.isValidAreaCode(...)
    //    Prefix:    non-zero
    //    Line num:  non-zero
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("2l. Phone 1 (optional): accepts null without error")
    void shouldAcceptWhenPhone1IsNull() {
        AccountDto dto = createValidDto();
        dto.setCustPhone1(null);

        assertThatCode(() -> accountValidator.validateUpdateFields(dto))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("2l. Phone 1 (optional): accepts empty string without error")
    void shouldAcceptWhenPhone1IsBlank() {
        AccountDto dto = createValidDto();
        dto.setCustPhone1("");

        assertThatCode(() -> accountValidator.validateUpdateFields(dto))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("2l. Phone 2 (optional): accepts null without error")
    void shouldAcceptWhenPhone2IsNull() {
        AccountDto dto = createValidDto();
        dto.setCustPhone2(null);

        assertThatCode(() -> accountValidator.validateUpdateFields(dto))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("2l. Phone 1: rejects fewer than 10 digits ('212555') with '10-digit' message")
    void shouldRejectWhenPhone1IsNotTenDigits() {
        AccountDto dto = createValidDto();
        dto.setCustPhone1("212555");

        assertThatThrownBy(() -> accountValidator.validateUpdateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).fieldName()).isEqualTo("custPhone1");
                    assertThat(errors.get(0).message())
                            .isEqualTo("Phone 1 must be a 10-digit phone number.");
                });
    }

    @Test
    @DisplayName("2l. Phone 1: rejects zero area code ('0005551234') with 'area code must not be zero'")
    void shouldRejectWhenPhone1HasZeroAreaCode() {
        AccountDto dto = createValidDto();
        dto.setCustPhone1("0005551234");

        assertThatThrownBy(() -> accountValidator.validateUpdateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    // Phone validation can emit multiple errors across area/prefix/line
                    assertThat(errors).extracting(FieldError::fieldName)
                            .contains("custPhone1");
                    assertThat(errors).extracting(FieldError::message)
                            .contains("Phone 1 area code must not be zero.");
                });
    }

    @Test
    @DisplayName("2l. Phone 1: rejects invalid NANPA area code ('1235551234') with NANPA message")
    void shouldRejectWhenPhone1HasInvalidNanpaAreaCode() {
        AccountDto dto = createValidDto();
        dto.setCustPhone1("1235551234");
        // Override lenient 'true' default to force NANPA rejection for area "123"
        when(validationLookupService.isValidAreaCode("123")).thenReturn(false);

        assertThatThrownBy(() -> accountValidator.validateUpdateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).fieldName()).isEqualTo("custPhone1");
                    // Note: NO trailing period per AccountValidator.java:622
                    assertThat(errors.get(0).message())
                            .isEqualTo("Phone 1: Not valid North America general purpose area code");
                });
    }

    @Test
    @DisplayName("2l. Phone 1: rejects zero prefix ('2120001234') with 'prefix must not be zero'")
    void shouldRejectWhenPhone1HasZeroPrefix() {
        AccountDto dto = createValidDto();
        dto.setCustPhone1("2120001234");

        assertThatThrownBy(() -> accountValidator.validateUpdateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors).extracting(FieldError::fieldName)
                            .contains("custPhone1");
                    assertThat(errors).extracting(FieldError::message)
                            .contains("Phone 1 prefix must not be zero.");
                });
    }

    @Test
    @DisplayName("2l. Phone 1: rejects zero line number ('2125550000') with 'line number must not be zero'")
    void shouldRejectWhenPhone1HasZeroLineNumber() {
        AccountDto dto = createValidDto();
        dto.setCustPhone1("2125550000");

        assertThatThrownBy(() -> accountValidator.validateUpdateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors).extracting(FieldError::fieldName)
                            .contains("custPhone1");
                    assertThat(errors).extracting(FieldError::message)
                            .contains("Phone 1 line number must not be zero.");
                });
    }

    @Test
    @DisplayName("2l. Phone 2: rejects invalid NANPA area code on Phone 2 (distinct label)")
    void shouldRejectWhenPhone2HasInvalidNanpaAreaCode() {
        AccountDto dto = createValidDto();
        dto.setCustPhone2("4445556789");
        when(validationLookupService.isValidAreaCode("444")).thenReturn(false);

        assertThatThrownBy(() -> accountValidator.validateUpdateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).fieldName()).isEqualTo("custPhone2");
                    assertThat(errors.get(0).message())
                            .isEqualTo("Phone 2: Not valid North America general purpose area code");
                });
    }

    @Test
    @DisplayName("2l. Phone 1: accepts valid 10-digit NANPA phone ('2125551234')")
    void shouldAcceptValidPhone1() {
        AccountDto dto = createValidDto();
        dto.setCustPhone1("2125551234");

        assertThatCode(() -> accountValidator.validateUpdateFields(dto))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("2l. Phone 1: accepts formatted phone ('(212) 555-1234') — non-digits stripped")
    void shouldAcceptFormattedPhone() {
        AccountDto dto = createValidDto();
        dto.setCustPhone1("(212) 555-1234");

        assertThatCode(() -> accountValidator.validateUpdateFields(dto))
                .doesNotThrowAnyException();
    }

    // -------------------------------------------------------------------------
    // SECTION 2m. EFT Account ID (step 23) — COACTUPC.cbl 1245-EDIT-NUM-REQD
    //    Field: custEftAcct | Label: "EFT Account ID" | Max length: 10
    //    Must be all numeric, non-empty, non-zero.
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("2m. EFT Account ID: rejects null with 'EFT Account ID must be supplied.'")
    void shouldRejectWhenEftAccountIsNull() {
        AccountDto dto = createValidDto();
        dto.setCustEftAcct(null);

        assertThatThrownBy(() -> accountValidator.validateUpdateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).fieldName()).isEqualTo("custEftAcct");
                    assertThat(errors.get(0).message())
                            .isEqualTo("EFT Account ID must be supplied.");
                });
    }

    @Test
    @DisplayName("2m. EFT Account ID: rejects blank with 'EFT Account ID must be supplied.'")
    void shouldRejectWhenEftAccountIsBlank() {
        AccountDto dto = createValidDto();
        dto.setCustEftAcct("   ");

        assertThatThrownBy(() -> accountValidator.validateUpdateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).fieldName()).isEqualTo("custEftAcct");
                    assertThat(errors.get(0).message())
                            .isEqualTo("EFT Account ID must be supplied.");
                });
    }

    @Test
    @DisplayName("2m. EFT Account ID: rejects non-numeric ('ABC') with 'EFT Account ID must be all numeric.'")
    void shouldRejectWhenEftAccountIsNotNumeric() {
        AccountDto dto = createValidDto();
        dto.setCustEftAcct("ABC1234567");

        assertThatThrownBy(() -> accountValidator.validateUpdateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).fieldName()).isEqualTo("custEftAcct");
                    assertThat(errors.get(0).message())
                            .isEqualTo("EFT Account ID must be all numeric.");
                });
    }

    @Test
    @DisplayName("2m. EFT Account ID: rejects length > 10 with 'EFT Account ID must not exceed 10 digits.'")
    void shouldRejectWhenEftAccountExceedsMaxLength() {
        AccountDto dto = createValidDto();
        dto.setCustEftAcct("12345678901"); // 11 digits

        assertThatThrownBy(() -> accountValidator.validateUpdateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).fieldName()).isEqualTo("custEftAcct");
                    assertThat(errors.get(0).message())
                            .isEqualTo("EFT Account ID must not exceed 10 digits.");
                });
    }

    @Test
    @DisplayName("2m. EFT Account ID: rejects all-zeros ('0000000000') with 'EFT Account ID must not be zero.'")
    void shouldRejectWhenEftAccountIsAllZeros() {
        AccountDto dto = createValidDto();
        dto.setCustEftAcct("0000000000");

        assertThatThrownBy(() -> accountValidator.validateUpdateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).fieldName()).isEqualTo("custEftAcct");
                    assertThat(errors.get(0).message())
                            .isEqualTo("EFT Account ID must not be zero.");
                });
    }

    @Test
    @DisplayName("2m. EFT Account ID: accepts valid 10-digit account ('1234567890')")
    void shouldAcceptValidEftAccount() {
        AccountDto dto = createValidDto();
        dto.setCustEftAcct("1234567890");

        assertThatCode(() -> accountValidator.validateUpdateFields(dto))
                .doesNotThrowAnyException();
    }

    // -------------------------------------------------------------------------
    // SECTION 2n. Primary Card Holder Indicator (step 24) — COACTUPC.cbl Y/N check
    //    Field: custProfileFlag | Label: "Primary Card Holder Indicator"
    //    Must be Y or N (case-insensitive).
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("2n. Profile Flag: rejects null with 'Primary Card Holder Indicator must be supplied.'")
    void shouldRejectWhenProfileFlagIsNull() {
        AccountDto dto = createValidDto();
        dto.setCustProfileFlag(null);

        assertThatThrownBy(() -> accountValidator.validateUpdateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).fieldName()).isEqualTo("custProfileFlag");
                    assertThat(errors.get(0).message())
                            .isEqualTo("Primary Card Holder Indicator must be supplied.");
                });
    }

    @Test
    @DisplayName("2n. Profile Flag: rejects invalid value ('Z') with 'must be Y or N.'")
    void shouldRejectWhenProfileFlagIsInvalid() {
        AccountDto dto = createValidDto();
        dto.setCustProfileFlag("Z");

        assertThatThrownBy(() -> accountValidator.validateUpdateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).fieldName()).isEqualTo("custProfileFlag");
                    assertThat(errors.get(0).message())
                            .isEqualTo("Primary Card Holder Indicator must be Y or N.");
                });
    }

    @Test
    @DisplayName("2n. Profile Flag: accepts 'Y'")
    void shouldAcceptProfileFlagY() {
        AccountDto dto = createValidDto();
        dto.setCustProfileFlag("Y");

        assertThatCode(() -> accountValidator.validateUpdateFields(dto))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("2n. Profile Flag: accepts 'N'")
    void shouldAcceptProfileFlagN() {
        AccountDto dto = createValidDto();
        dto.setCustProfileFlag("N");

        assertThatCode(() -> accountValidator.validateUpdateFields(dto))
                .doesNotThrowAnyException();
    }

    // -------------------------------------------------------------------------
    // SECTION 2o. Cross-Field State/ZIP Prefix Validation (step 25) —
    //             COACTUPC.cbl 1280-EDIT-US-STATE-ZIP
    //    Fires ONLY when BOTH individual state AND zip validations passed.
    //    Delegates to ValidationLookupService.isValidStateZipPrefix(state, zip).
    //    FieldKey on failure is "custZip" (NOT "custState").
    //    Error message: "Invalid zip code for state " + dto.getCustState()
    //       (raw unmodified — not uppercased).
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("2o. Cross-field: rejects state/zip mismatch with fieldKey 'custZip' and raw state in message")
    void shouldRejectWhenStateZipMismatch() {
        AccountDto dto = createValidDto();
        dto.setCustState("CA");
        dto.setCustZip("90210");
        // State and ZIP individually valid (lenient stubs) — force cross-field lookup false
        when(validationLookupService.isValidStateZipPrefix("CA", "90210")).thenReturn(false);

        assertThatThrownBy(() -> accountValidator.validateUpdateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors).hasSize(1);
                    // Cross-field mismatch reports against the ZIP field, not State
                    assertThat(errors.get(0).fieldName()).isEqualTo("custZip");
                    assertThat(errors.get(0).rejectedValue()).isEqualTo("90210");
                    // State is included RAW (not uppercased) in the message
                    assertThat(errors.get(0).message())
                            .isEqualTo("Invalid zip code for state CA");
                });
    }

    @Test
    @DisplayName("2o. Cross-field: skips lookup when State is individually invalid")
    void shouldSkipStateZipCrossValidationWhenStateInvalid() {
        AccountDto dto = createValidDto();
        dto.setCustState("XX"); // passes alpha regex
        dto.setCustZip("90210"); // passes zip regex
        // Force State lookup to fail so stateValid=false inside validator
        when(validationLookupService.isValidStateCode("XX")).thenReturn(false);
        // Cross-field lookup (isValidStateZipPrefix) MUST NOT be invoked here
        // because stateValid=false short-circuits the cross-field step 25. The
        // @BeforeEach lenient default returns true for isValidStateZipPrefix,
        // so we intentionally do NOT add an additional stub to prove skipping.

        assertThatThrownBy(() -> accountValidator.validateUpdateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    // Only ONE error — the state error — cross-field skipped
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).fieldName()).isEqualTo("custState");
                    assertThat(errors.get(0).message())
                            .isEqualTo("State: is not a valid state code");
                    // Explicitly verify no custZip error was emitted from cross-field
                    assertThat(errors).extracting(FieldError::fieldName)
                            .doesNotContain("custZip");
                });
    }

    @Test
    @DisplayName("2o. Cross-field: skips lookup when ZIP is individually invalid")
    void shouldSkipStateZipCrossValidationWhenZipInvalid() {
        AccountDto dto = createValidDto();
        dto.setCustState("CA"); // valid alpha + valid code (lenient stub)
        dto.setCustZip("00000"); // fails all-zeros check → zipValid=false
        // Cross-field lookup (isValidStateZipPrefix) MUST NOT be invoked here
        // because zipValid=false short-circuits the cross-field step 25. The
        // @BeforeEach lenient default returns true for isValidStateZipPrefix,
        // so we intentionally do NOT add an additional stub to prove skipping.

        assertThatThrownBy(() -> accountValidator.validateUpdateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    // Only the zip 'must not be zero' error — cross-field skipped
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).fieldName()).isEqualTo("custZip");
                    assertThat(errors.get(0).message())
                            .isEqualTo("ZIP Code must not be zero.");
                });
    }

    @Test
    @DisplayName("2o. Cross-field: passes when both state and zip individually valid AND prefix matches")
    void shouldAcceptStateZipWhenPrefixMatches() {
        AccountDto dto = createValidDto();
        dto.setCustState("CA");
        dto.setCustZip("90210");
        when(validationLookupService.isValidStateZipPrefix("CA", "90210")).thenReturn(true);

        assertThatCode(() -> accountValidator.validateUpdateFields(dto))
                .doesNotThrowAnyException();
    }



    // -------------------------------------------------------------------------
    // SECTION 2p. Error Aggregation — validateUpdateFields accumulates ALL
    //             FieldErrors across 25 cascade steps and throws ONCE.
    //             (In contrast to validateAccountId's first-fail-throw.)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("2p. Aggregates multiple field errors across the 25-step cascade into one ValidationException")
    void shouldAggregateMultipleFieldErrors() {
        AccountDto dto = createValidDto();
        // Three independently-invalid fields:
        dto.setAcctActiveStatus("X");     // invalid Y/N (step 1)
        dto.setCustSsn("000456789");      // SSN Part 1 = 000 (step 10)
        dto.setCustFicoScore("200");      // below FICO min 300 (step 12)

        assertThatThrownBy(() -> accountValidator.validateUpdateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors).hasSize(3);
                    assertThat(errors).extracting(FieldError::fieldName)
                            .containsExactlyInAnyOrder(
                                    "acctActiveStatus",
                                    "custSsn",
                                    "custFicoScore");
                    assertThat(errors).extracting(FieldError::message)
                            .containsExactlyInAnyOrder(
                                    "Account Status must be Y or N.",
                                    "SSN: First 3 chars: should not be 000, 666, or between 900 and 999",
                                    "FICO Score: should be between 300 and 850");
                });
    }

    @Test
    @DisplayName("2p. Aggregates errors from distinct validation categories (date + monetary + regex)")
    void shouldAggregateErrorsFromDistinctCategories() {
        AccountDto dto = createValidDto();
        dto.setCustFname("John123");       // alpha regex
        dto.setAcctCreditLimit(null);      // monetary null
        // Date validation failure via mock override
        when(dateValidationService.validateDate(anyString(), eq(LABEL_OPEN_DATE)))
                .thenReturn(invalidDateResult("Year is not valid"));

        assertThatThrownBy(() -> accountValidator.validateUpdateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors).hasSize(3);
                    assertThat(errors).extracting(FieldError::fieldName)
                            .containsExactlyInAnyOrder(
                                    "custFname",
                                    "acctCreditLimit",
                                    "acctOpenDate");
                });
    }

    // -------------------------------------------------------------------------
    // SECTION 2q. Happy Path — fully valid DTO passes through all 25 steps
    //             + cross-field with NO ValidationException.
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("2q. Happy path: fully valid DTO passes all 25 cascade steps and cross-field")
    void shouldAcceptFullyValidDto() {
        AccountDto dto = createValidDto();

        assertThatCode(() -> accountValidator.validateUpdateFields(dto))
                .doesNotThrowAnyException();
    }

    // =========================================================================
    // HELPER METHODS
    //
    // createValidDto() — constructs an AccountDto where every field passes all
    // 25 validation cascade steps plus the cross-field state/zip check, given
    // the lenient stubs installed in setUp() (validateDate returns valid,
    // validateDateOfBirth returns valid, isValidStateCode/isValidAreaCode/
    // isValidStateZipPrefix return true by default).
    //
    // Mirrors the createValidDto() helper from AccountUpdateServiceTest.java
    // (source test lines ~758-789) verbatim to maintain 100% behavioral parity
    // per AAP Rule R-003.
    //
    // validDateResult() / invalidDateResult(msg) — factory helpers for
    // DateValidationService.DateValidationResult record (7-arg canonical
    // constructor: valid, severityCode, resultMessage, fullMessage,
    // yearValid, monthValid, dayValid) used by mock stubbing.
    // =========================================================================

    /**
     * Builds a fully-populated AccountDto with valid values for every field
     * validated by AccountValidator.validateUpdateFields(...).
     *
     * <p>Fields are populated to satisfy:
     * <ul>
     *   <li>Step 1 (acctActiveStatus): "Y" — valid Y/N</li>
     *   <li>Steps 2,4,6 (dates): LocalDates that format to CCYYMMDD — stubbed valid</li>
     *   <li>Steps 3,5,7,8,9 (monetary): non-null BigDecimal</li>
     *   <li>Step 10 (SSN): "123456789" — all parts pass (Part1=123, Part2=45, Part3=6789)</li>
     *   <li>Step 11 (DOB): LocalDate — stubbed valid</li>
     *   <li>Step 12 (FICO): "750" — within [300, 850]</li>
     *   <li>Steps 13,14,15 (names): alpha-only with allowed spaces</li>
     *   <li>Step 16 (addr1): mandatory non-blank</li>
     *   <li>Step 17 (state): "CA" — passes alpha regex + lookup stub</li>
     *   <li>Step 18 (zip): "90210" — passes 5-digit regex, non-zero</li>
     *   <li>Step 19 (city): "Los Angeles" — alpha + space allowed</li>
     *   <li>Step 20 (country): "US" — alpha, length ≤ 3</li>
     *   <li>Steps 21,22 (phones): "2125551234" — valid NANPA area "212" stubbed</li>
     *   <li>Step 23 (EFT): "1234567890" — numeric, non-zero, length ≤ 10</li>
     *   <li>Step 24 (profile flag): "Y" — valid Y/N</li>
     *   <li>Step 25 (cross-field state+zip): CA+90210 — stubbed valid</li>
     * </ul>
     *
     * <p>Maps COBOL CVACT01Y.cpy ACCT-RECORD and CVCUS01Y.cpy CUSTOMER-RECORD.
     */
    private AccountDto createValidDto() {
        AccountDto dto = new AccountDto();
        // --- ACCT-RECORD fields (CVACT01Y.cpy) ---
        dto.setAcctId(VALID_ACCT_ID);                           // ACCT-ID PIC 9(11)
        dto.setAcctActiveStatus(VALID_ACTIVE_STATUS);           // ACCT-ACTIVE-STATUS PIC X(01)
        dto.setAcctCurrBal(new BigDecimal("1500.75"));          // ACCT-CURR-BAL COMP-3
        dto.setAcctCreditLimit(new BigDecimal("10000.00"));     // ACCT-CREDIT-LIMIT COMP-3
        dto.setAcctCashCreditLimit(new BigDecimal("2500.00"));  // ACCT-CASH-CREDIT-LIMIT COMP-3
        dto.setAcctCurrCycCredit(new BigDecimal("250.00"));     // ACCT-CURR-CYC-CREDIT COMP-3
        dto.setAcctCurrCycDebit(new BigDecimal("100.00"));      // ACCT-CURR-CYC-DEBIT COMP-3
        dto.setAcctOpenDate(LocalDate.of(2020, 1, 15));         // ACCT-OPEN-DATE CCYYMMDD
        dto.setAcctExpDate(LocalDate.of(2027, 12, 31));         // ACCT-EXPIRATION-DATE CCYYMMDD
        dto.setAcctReissueDate(LocalDate.of(2025, 6, 15));      // ACCT-REISSUE-DATE CCYYMMDD
        dto.setAcctGroupId("000000001");                        // ACCT-GROUP-ID PIC X(10)
        // --- CUSTOMER-RECORD fields (CVCUS01Y.cpy) ---
        dto.setCustFname("John");                               // CUST-FIRST-NAME PIC X(25)
        dto.setCustMname("Michael");                            // CUST-MIDDLE-NAME PIC X(25) [optional]
        dto.setCustLname("Smith");                              // CUST-LAST-NAME PIC X(25)
        dto.setCustAddr1("123 Main Street");                    // CUST-ADDR-LINE-1 PIC X(50)
        dto.setCustAddr2("Apt 4B");                             // CUST-ADDR-LINE-2 PIC X(50)
        dto.setCustCity("Los Angeles");                         // CUST-ADDR-CITY PIC X(50)
        dto.setCustState(VALID_STATE);                          // CUST-ADDR-STATE-CD PIC X(02)
        dto.setCustZip(VALID_ZIP);                              // CUST-ADDR-ZIP PIC X(10)
        dto.setCustCountry("US");                               // CUST-ADDR-COUNTRY-CD PIC X(03)
        dto.setCustPhone1(VALID_PHONE);                         // CUST-PHONE-NUM-1 PIC X(15)
        dto.setCustPhone2("3105559876");                        // CUST-PHONE-NUM-2 PIC X(15)
        dto.setCustSsn(VALID_SSN);                              // CUST-SSN PIC 9(09)
        dto.setCustDob(LocalDate.of(1985, 5, 20));              // CUST-DOB-YYYY-MM-DD CCYYMMDD
        dto.setCustFicoScore(VALID_FICO);                       // CUST-FICO-CREDIT-SCORE PIC 9(03)
        dto.setCustGovtId("DL12345678");                        // CUST-GOVT-ISSUED-ID PIC X(20)
        dto.setCustEftAcct(VALID_EFT);                          // CUST-EFT-ACCOUNT-ID PIC X(10)
        dto.setCustProfileFlag("Y");                            // CUST-PRI-CARD-HOLDER-IND PIC X(01)
        return dto;
    }

    /**
     * Builds a DateValidationResult representing a valid date outcome — used
     * to stub DateValidationService.validateDate(...) and validateDateOfBirth(...)
     * in {@link #setUp()} so happy-path tests pass all date validations without
     * exercising the real CSUTLDTC.cbl + CEEDAYS cascade.
     *
     * <p>Mirrors the helper from AccountUpdateServiceTest.java verbatim.
     */
    private DateValidationService.DateValidationResult validDateResult() {
        return new DateValidationService.DateValidationResult(
                true,                       // valid
                0,                          // severityCode (0 = informational)
                "Date is valid",            // resultMessage
                "Date is valid",            // fullMessage
                true,                       // yearValid
                true,                       // monthValid
                true                        // dayValid
        );
    }

    /**
     * Builds a DateValidationResult representing an invalid date outcome with
     * the given message — used by individual tests to override the lenient
     * happy-path stub and force a specific date validation failure.
     *
     * <p>Severity 12 mirrors COBOL CEEDAYS severity-12 (unrecoverable error).
     *
     * <p>Mirrors the helper from AccountUpdateServiceTest.java verbatim.
     */
    private DateValidationService.DateValidationResult invalidDateResult(String message) {
        return new DateValidationService.DateValidationResult(
                false,                      // valid
                12,                         // severityCode (12 = unrecoverable)
                message,                    // resultMessage
                message,                    // fullMessage
                true,                       // yearValid   (immaterial when valid=false)
                true,                       // monthValid  (immaterial when valid=false)
                true                        // dayValid    (immaterial when valid=false)
        );
    }
}

