/*
 * CardValidatorTest.java — Pure JUnit 5 + AssertJ Unit Tests for the
 * Domain-Layer Card Field Validator.
 *
 * Tests {@link com.cardemo.domain.validation.CardValidator} — the validator
 * extracted from {@code com.cardemo.service.card.CardUpdateService} per AAP
 * §0.5.1 (Domain Validation Pattern). Exercises the 5-field validation
 * cascade (account ID → card number → embossed name → active status →
 * expiry date) that mirrors the COBOL paragraph sequence from
 * {@code COCRDUPC.cbl} 1200-EDIT-MAP-INPUTS.
 *
 * COBOL Source Traceability (AAP Rule R-004 — preserved verbatim):
 *   - app/cbl/COCRDUPC.cbl (online card update — 1,560 lines; paragraphs
 *     1210-EDIT-ACCOUNT, 1220-EDIT-CARD, 1230-EDIT-NAME, 1240-EDIT-CARDSTATUS,
 *     1250-EDIT-EXPIRY-MON, 1260-EDIT-EXPIRY-YEAR)
 *   - app/cpy/CVACT02Y.cpy (CARD-RECORD 150-byte VSAM record layout —
 *     CARD-NUM PIC X(16), CARD-ACCT-ID PIC 9(11), CARD-EMBOSSED-NAME PIC X(50),
 *     CARD-EXPIRAION-DATE PIC X(10), CARD-ACTIVE-STATUS PIC X(01))
 *   - app/cpy/COCRDUP.CPY (CCRDUPAI symbolic map — ACCTSIDI, CARDSIDI,
 *     CRDNAMEI, CRDSTCDI, EXPMONI, EXPDAYI, EXPYEARI)
 *   - CCARDAT (online card update BMS map)
 *
 * Testing Approach:
 *   Pure JUnit 5 + AssertJ unit tests — NO Spring context loading, NO Mockito
 *   collaborator stubbing, NO external integration (no database, no AWS, no
 *   HTTP). {@code CardValidator} has ZERO Spring-injected dependencies
 *   (constants come from {@code CardConstants}; regex patterns come from
 *   {@code ValidationPatterns}), so direct instantiation via
 *   {@code new CardValidator()} in {@code @BeforeEach} is the correct
 *   isolation pattern. The tests are the authoritative behavioral
 *   specification for CardValidator and complement the Mockito-based
 *   {@link com.cardemo.unit.service.CardUpdateServiceTest} which mocks
 *   CardValidator rather than exercising its real logic.
 *
 * Source Extraction Note:
 *   These tests mirror the validation scenarios previously embedded in
 *   {@code CardUpdateServiceTest} that covered the 5-field cascade within
 *   the monolithic service. With the extraction to the domain layer, the
 *   behavioral assertions are now the first-class responsibility of this
 *   test class (per AAP Phase 2 §0.5.1 — "CREATE tests for extracted
 *   domain classes").
 *
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 * Licensed under the Apache License, Version 2.0
 */
package com.cardemo.unit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cardemo.domain.validation.CardValidator;
import com.cardemo.exception.ValidationException;
import com.cardemo.exception.ValidationException.FieldError;
import com.cardemo.model.dto.CardDto;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

/**
 * Pure JUnit 5 + AssertJ unit tests for {@link CardValidator} — the
 * domain-layer card field validator extracted from
 * {@code CardUpdateService} during the refactoring (AAP Section 0.4.1,
 * "Domain Validation Pattern").
 *
 * <p>Each test exercises exactly one branch of the 5-field validation
 * cascade originally migrated from {@code COCRDUPC.cbl} paragraphs
 * 1210–1260. Tests verify the exact field name, rejected-value masking
 * (PCI DSS compliance), and error message produced by the validator for
 * each failure mode, plus the happy path and the multi-error aggregation
 * pattern that mirrors COBOL's batch-redisplay workflow.</p>
 *
 * <p><b>Critical Invariants Tested:</b></p>
 * <ul>
 *   <li>Field names in {@link FieldError#fieldName()} exactly match the
 *       BMS symbolic map field names — {@code "acctId"}, {@code "cardNum"},
 *       {@code "embossedName"}, {@code "activeStatus"},
 *       {@code "expMonth"}, {@code "expYear"}.</li>
 *   <li>A {@code null} {@link CardDto#getCardExpDate() cardExpDate}
 *       produces TWO separate {@link FieldError} entries (one for
 *       {@code "expMonth"} and one for {@code "expYear"}) with the Java
 *       {@code null} reference as the {@code rejectedValue} — NOT the
 *       literal {@code String} {@code "null"}. This matches the COBOL
 *       behavior in paragraphs 1250-EDIT-EXPIRY-MON and
 *       1260-EDIT-EXPIRY-YEAR where EXPMONI and EXPYEARI are independent
 *       BMS fields.</li>
 *   <li>Invalid {@code cardNum} and {@code acctId} values are masked in
 *       the {@code rejectedValue} (format {@code "****"} + last 4 digits)
 *       to prevent full-PAN exposure in log files and error payloads.
 *       Invalid {@code embossedName} and {@code activeStatus} values are
 *       passed through unmodified (no masking).</li>
 *   <li>All field failures from a single input DTO accumulate into one
 *       {@link ValidationException} (error-aggregation pattern), not
 *       throw-on-first-failure.</li>
 * </ul>
 *
 * @see CardValidator
 * @see com.cardemo.service.card.CardUpdateService
 * @see com.cardemo.unit.service.CardUpdateServiceTest
 */
@DisplayName("CardValidator — COCRDUPC.cbl Card Field Validation Unit Tests")
class CardValidatorTest {

    /* --------------------------------------------------------------------
     * Test fixture constants — mirror CVACT02Y.cpy CARD-RECORD valid values
     * used throughout CardUpdateServiceTest. Alignment with the service
     * test ensures the extracted validator is exercised with the same
     * canonical inputs that the monolithic service used prior to the
     * domain-layer refactoring.
     * ------------------------------------------------------------------ */

    /** Valid 11-digit numeric account ID (CARD-ACCT-ID PIC 9(11)). */
    private static final String VALID_ACCT_ID = "00000000001";

    /** Valid 16-digit numeric card number (CARD-NUM PIC X(16)). */
    private static final String VALID_CARD_NUM = "4111111111111111";

    /** Valid embossed cardholder name (CARD-EMBOSSED-NAME PIC X(50)). */
    private static final String VALID_EMBOSSED_NAME = "JOHN DOE";

    /** Valid active-status flag 'Y' (CARD-ACTIVE-STATUS PIC X(01)). */
    private static final String VALID_ACTIVE_STATUS = "Y";

    /**
     * Valid expiration date within MIN_EXPIRY_YEAR (1950) –
     * MAX_EXPIRY_YEAR (2099). Day component defaults per COBOL convention
     * (not user-editable). (CARD-EXPIRAION-DATE PIC X(10).)
     */
    private static final LocalDate VALID_EXPIRY_DATE = LocalDate.of(2030, 12, 31);

    /** Valid CVV code (CARD-CVV-CD PIC 9(03)) — CardValidator does not
     *  validate CVV but it is included in the DTO fixture for completeness. */
    private static final String VALID_CVV = "123";

    /* --------------------------------------------------------------------
     * Subject under test
     * ------------------------------------------------------------------ */

    /**
     * The validator instance under test. Re-instantiated before every
     * test method by {@link #setUp()} to guarantee test isolation.
     * {@link CardValidator} has no collaborators and holds no mutable
     * state, so a fresh instance per test is inexpensive.
     */
    private CardValidator cardValidator;

    /**
     * Instantiate a fresh {@link CardValidator} before each test method
     * via its public no-arg constructor. No Spring context is needed —
     * {@link CardValidator} depends only on static constants and precompiled
     * regex patterns from {@code CardConstants} / {@code ValidationPatterns}.
     */
    @BeforeEach
    void setUp() {
        cardValidator = new CardValidator();
    }

    /* ====================================================================
     * SECTION 1 — Account ID Validation (COBOL 1210-EDIT-ACCOUNT)
     * Field: "acctId"  |  CARD-ACCT-ID PIC 9(11)  |  BMS ACCTSIDI
     * ================================================================== */

    @Test
    @DisplayName("acctId: blank (empty string) produces 'Account number cannot be blank' error with pass-through rejectedValue")
    void shouldRejectWhenAccountIdIsBlank() {
        CardDto dto = validDto();
        dto.setCardAcctId("");

        assertThatThrownBy(() -> cardValidator.validateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors)
                            .filteredOn(e -> "acctId".equals(e.fieldName()))
                            .hasSize(1)
                            .first()
                            .satisfies(err -> {
                                // Blank input is passed through unmodified (not masked)
                                assertThat(err.rejectedValue()).isEqualTo("");
                                assertThat(err.message()).isEqualTo("Account number cannot be blank");
                            });
                });
    }

    @Test
    @DisplayName("acctId: null value produces 'Account number cannot be blank' error with null rejectedValue")
    void shouldRejectWhenAccountIdIsNull() {
        CardDto dto = validDto();
        dto.setCardAcctId(null);

        assertThatThrownBy(() -> cardValidator.validateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors)
                            .filteredOn(e -> "acctId".equals(e.fieldName()))
                            .hasSize(1)
                            .first()
                            .satisfies(err -> {
                                // Null input is passed through as a null Java reference
                                assertThat(err.rejectedValue()).isNull();
                                assertThat(err.message()).isEqualTo("Account number cannot be blank");
                            });
                });
    }

    @Test
    @DisplayName("acctId: whitespace-only value produces 'Account number cannot be blank' error")
    void shouldRejectWhenAccountIdIsWhitespaceOnly() {
        CardDto dto = validDto();
        dto.setCardAcctId("   ");

        assertThatThrownBy(() -> cardValidator.validateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors)
                            .filteredOn(e -> "acctId".equals(e.fieldName()))
                            .hasSize(1)
                            .first()
                            .satisfies(err -> {
                                // Whitespace input is passed through unmodified (not masked)
                                assertThat(err.rejectedValue()).isEqualTo("   ");
                                assertThat(err.message()).isEqualTo("Account number cannot be blank");
                            });
                });
    }

    @Test
    @DisplayName("acctId: non-numeric value produces 'Account number must be numeric' error with masked rejectedValue")
    void shouldRejectWhenAccountIdHasNonNumeric() {
        CardDto dto = validDto();
        dto.setCardAcctId("ABC12345678");

        assertThatThrownBy(() -> cardValidator.validateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors)
                            .filteredOn(e -> "acctId".equals(e.fieldName()))
                            .hasSize(1)
                            .first()
                            .satisfies(err -> {
                                // Invalid account IDs are masked to last 4 visible digits
                                // (MASK_VISIBLE_DIGITS=4) for PCI-adjacent safety
                                assertThat(err.rejectedValue()).isEqualTo("****5678");
                                assertThat(err.message()).isEqualTo("Account number must be numeric");
                            });
                });
    }

    @Test
    @DisplayName("acctId: 12-digit value exceeds ACCT_ID_MAX_LENGTH=11 and produces masked length error")
    void shouldRejectWhenAccountIdExceedsMaxLength() {
        CardDto dto = validDto();
        dto.setCardAcctId("123456789012"); // 12 digits — one over the limit

        assertThatThrownBy(() -> cardValidator.validateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors)
                            .filteredOn(e -> "acctId".equals(e.fieldName()))
                            .hasSize(1)
                            .first()
                            .satisfies(err -> {
                                assertThat(err.rejectedValue()).isEqualTo("****9012");
                                assertThat(err.message())
                                        .isEqualTo("Account number must not exceed 11 digits");
                            });
                });
    }

    @Test
    @DisplayName("acctId: valid 11-digit numeric value does not produce an acctId error")
    void shouldAcceptValidAccountId() {
        CardDto dto = validDto();
        // All fields valid → validator must not throw
        assertThatCode(() -> cardValidator.validateFields(dto)).doesNotThrowAnyException();
    }

    /* ====================================================================
     * SECTION 2 — Card Number Validation (COBOL 1220-EDIT-CARD)
     * Field: "cardNum"  |  CARD-NUM PIC X(16)  |  BMS CARDSIDI
     * ================================================================== */

    @Test
    @DisplayName("cardNum: blank (empty string) produces 'Card number cannot be blank' error")
    void shouldRejectWhenCardNumberIsBlank() {
        CardDto dto = validDto();
        dto.setCardNum("");

        assertThatThrownBy(() -> cardValidator.validateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors)
                            .filteredOn(e -> "cardNum".equals(e.fieldName()))
                            .hasSize(1)
                            .first()
                            .satisfies(err -> {
                                // Blank input is passed through unmodified
                                assertThat(err.rejectedValue()).isEqualTo("");
                                assertThat(err.message()).isEqualTo("Card number cannot be blank");
                            });
                });
    }

    @Test
    @DisplayName("cardNum: null value produces 'Card number cannot be blank' error with null rejectedValue")
    void shouldRejectWhenCardNumberIsNull() {
        CardDto dto = validDto();
        dto.setCardNum(null);

        assertThatThrownBy(() -> cardValidator.validateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors)
                            .filteredOn(e -> "cardNum".equals(e.fieldName()))
                            .hasSize(1)
                            .first()
                            .satisfies(err -> {
                                assertThat(err.rejectedValue()).isNull();
                                assertThat(err.message()).isEqualTo("Card number cannot be blank");
                            });
                });
    }

    @Test
    @DisplayName("cardNum: value containing letters is non-numeric and produces masked error")
    void shouldRejectWhenCardNumberHasLetters() {
        CardDto dto = validDto();
        dto.setCardNum("411111111111ABCD"); // 16 chars with trailing letters

        assertThatThrownBy(() -> cardValidator.validateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors)
                            .filteredOn(e -> "cardNum".equals(e.fieldName()))
                            .hasSize(1)
                            .first()
                            .satisfies(err -> {
                                // PAN masking: last 4 chars visible, "****" prefix
                                assertThat(err.rejectedValue()).isEqualTo("****ABCD");
                                assertThat(err.message()).isEqualTo("Card number must be numeric");
                            });
                });
    }

    @Test
    @DisplayName("cardNum: 20-digit value exceeds CARD_NUM_MAX_LENGTH=16 and produces masked length error")
    void shouldRejectWhenCardNumberExceedsMaxLength() {
        CardDto dto = validDto();
        dto.setCardNum("41111111111111119999"); // 20 digits — four over the limit

        assertThatThrownBy(() -> cardValidator.validateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors)
                            .filteredOn(e -> "cardNum".equals(e.fieldName()))
                            .hasSize(1)
                            .first()
                            .satisfies(err -> {
                                // PAN masking: "****" + last 4 digits (9999) — NOT full input
                                assertThat(err.rejectedValue()).isEqualTo("****9999");
                                assertThat(err.message())
                                        .isEqualTo("Card number must not exceed 16 digits");
                            });
                });
    }

    @Test
    @DisplayName("cardNum: valid 16-digit numeric value does not produce a cardNum error")
    void shouldAcceptValidCardNumber() {
        CardDto dto = validDto();
        // VALID_CARD_NUM is exactly 16 digits — at the upper boundary of CARD_NUM_MAX_LENGTH
        assertThatCode(() -> cardValidator.validateFields(dto)).doesNotThrowAnyException();
    }

    /* ====================================================================
     * SECTION 3 — Embossed Name Validation (COBOL 1230-EDIT-NAME)
     * Field: "embossedName"  |  CARD-EMBOSSED-NAME PIC X(50)  |  BMS CRDNAMEI
     * Pattern: ALPHA_SPACE_PATTERN = ^[A-Za-z ]+$
     * ================================================================== */

    @Test
    @DisplayName("embossedName: blank value produces 'Name must contain only letters and spaces' error (pass-through)")
    void shouldRejectWhenEmbossedNameIsBlank() {
        CardDto dto = validDto();
        dto.setCardEmbossedName("");

        assertThatThrownBy(() -> cardValidator.validateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors)
                            .filteredOn(e -> "embossedName".equals(e.fieldName()))
                            .hasSize(1)
                            .first()
                            .satisfies(err -> {
                                // Embossed names are NOT masked — passed through unmodified
                                assertThat(err.rejectedValue()).isEqualTo("");
                                assertThat(err.message())
                                        .isEqualTo("Name must contain only letters and spaces");
                            });
                });
    }

    @Test
    @DisplayName("embossedName: null value produces 'Name must contain only letters and spaces' error")
    void shouldRejectWhenEmbossedNameIsNull() {
        CardDto dto = validDto();
        dto.setCardEmbossedName(null);

        assertThatThrownBy(() -> cardValidator.validateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors)
                            .filteredOn(e -> "embossedName".equals(e.fieldName()))
                            .hasSize(1)
                            .first()
                            .satisfies(err -> {
                                assertThat(err.rejectedValue()).isNull();
                                assertThat(err.message())
                                        .isEqualTo("Name must contain only letters and spaces");
                            });
                });
    }

    @Test
    @DisplayName("embossedName: value with digits violates ALPHA_SPACE_PATTERN and is passed through (not masked)")
    void shouldRejectWhenEmbossedNameHasDigits() {
        CardDto dto = validDto();
        dto.setCardEmbossedName("JOHN123");

        assertThatThrownBy(() -> cardValidator.validateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors)
                            .filteredOn(e -> "embossedName".equals(e.fieldName()))
                            .hasSize(1)
                            .first()
                            .satisfies(err -> {
                                // Not masked — full value passed through for operator correction
                                assertThat(err.rejectedValue()).isEqualTo("JOHN123");
                                assertThat(err.message())
                                        .isEqualTo("Name must contain only letters and spaces");
                            });
                });
    }

    @Test
    @DisplayName("embossedName: value with special characters violates ALPHA_SPACE_PATTERN")
    void shouldRejectWhenEmbossedNameHasSpecialChars() {
        CardDto dto = validDto();
        dto.setCardEmbossedName("JOHN-DOE");

        assertThatThrownBy(() -> cardValidator.validateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors)
                            .filteredOn(e -> "embossedName".equals(e.fieldName()))
                            .hasSize(1)
                            .first()
                            .satisfies(err -> {
                                assertThat(err.rejectedValue()).isEqualTo("JOHN-DOE");
                                assertThat(err.message())
                                        .isEqualTo("Name must contain only letters and spaces");
                            });
                });
    }

    @Test
    @DisplayName("embossedName: multi-word name with interior spaces is accepted")
    void shouldAcceptEmbossedNameWithSpaces() {
        CardDto dto = validDto();
        dto.setCardEmbossedName("JOHN PAUL DOE");
        assertThatCode(() -> cardValidator.validateFields(dto)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("embossedName: mixed-case name is accepted (ALPHA_SPACE_PATTERN is case-insensitive)")
    void shouldAcceptMixedCaseEmbossedName() {
        CardDto dto = validDto();
        dto.setCardEmbossedName("John Doe");
        assertThatCode(() -> cardValidator.validateFields(dto)).doesNotThrowAnyException();
    }

    /* ====================================================================
     * SECTION 4 — Active Status Validation (COBOL 1240-EDIT-CARDSTATUS)
     * Field: "activeStatus"  |  CARD-ACTIVE-STATUS PIC X(01)  |  BMS CRDSTCDI
     * ================================================================== */

    @Test
    @DisplayName("activeStatus: blank value produces 'Card status must be Y or N' error")
    void shouldRejectWhenActiveStatusIsBlank() {
        CardDto dto = validDto();
        dto.setCardActiveStatus("");

        assertThatThrownBy(() -> cardValidator.validateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors)
                            .filteredOn(e -> "activeStatus".equals(e.fieldName()))
                            .hasSize(1)
                            .first()
                            .satisfies(err -> {
                                // Pass-through (no masking for single-char status)
                                assertThat(err.rejectedValue()).isEqualTo("");
                                assertThat(err.message()).isEqualTo("Card status must be Y or N");
                            });
                });
    }

    @Test
    @DisplayName("activeStatus: null value produces 'Card status must be Y or N' error with null rejectedValue")
    void shouldRejectWhenActiveStatusIsNull() {
        CardDto dto = validDto();
        dto.setCardActiveStatus(null);

        assertThatThrownBy(() -> cardValidator.validateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors)
                            .filteredOn(e -> "activeStatus".equals(e.fieldName()))
                            .hasSize(1)
                            .first()
                            .satisfies(err -> {
                                assertThat(err.rejectedValue()).isNull();
                                assertThat(err.message()).isEqualTo("Card status must be Y or N");
                            });
                });
    }

    @Test
    @DisplayName("activeStatus: invalid letter 'X' produces error with ORIGINAL-CASE pass-through rejectedValue")
    void shouldRejectWhenActiveStatusIsInvalidLetter() {
        CardDto dto = validDto();
        dto.setCardActiveStatus("X");

        assertThatThrownBy(() -> cardValidator.validateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors)
                            .filteredOn(e -> "activeStatus".equals(e.fieldName()))
                            .hasSize(1)
                            .first()
                            .satisfies(err -> {
                                // Original case preserved in rejectedValue (not uppercased)
                                assertThat(err.rejectedValue()).isEqualTo("X");
                                assertThat(err.message()).isEqualTo("Card status must be Y or N");
                            });
                });
    }

    @Test
    @DisplayName("activeStatus: lowercase invalid letter 'x' produces error with lowercase pass-through")
    void shouldRejectWhenActiveStatusIsInvalidLowercaseLetter() {
        CardDto dto = validDto();
        dto.setCardActiveStatus("x");

        assertThatThrownBy(() -> cardValidator.validateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors)
                            .filteredOn(e -> "activeStatus".equals(e.fieldName()))
                            .hasSize(1)
                            .first()
                            .satisfies(err -> {
                                // Original lowercase preserved; not uppercased in the error
                                assertThat(err.rejectedValue()).isEqualTo("x");
                                assertThat(err.message()).isEqualTo("Card status must be Y or N");
                            });
                });
    }

    @Test
    @DisplayName("activeStatus: uppercase 'Y' is accepted")
    void shouldAcceptActiveStatusY() {
        CardDto dto = validDto();
        dto.setCardActiveStatus("Y");
        assertThatCode(() -> cardValidator.validateFields(dto)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("activeStatus: uppercase 'N' is accepted")
    void shouldAcceptActiveStatusN() {
        CardDto dto = validDto();
        dto.setCardActiveStatus("N");
        assertThatCode(() -> cardValidator.validateFields(dto)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("activeStatus: lowercase 'y' is accepted (case-insensitive via toUpperCase)")
    void shouldAcceptLowercaseActiveStatusY() {
        CardDto dto = validDto();
        dto.setCardActiveStatus("y");
        assertThatCode(() -> cardValidator.validateFields(dto)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("activeStatus: lowercase 'n' is accepted (case-insensitive via toUpperCase)")
    void shouldAcceptLowercaseActiveStatusN() {
        CardDto dto = validDto();
        dto.setCardActiveStatus("n");
        assertThatCode(() -> cardValidator.validateFields(dto)).doesNotThrowAnyException();
    }

    /* ====================================================================
     * SECTION 5 — Expiry Date Validation (COBOL 1250-EDIT-EXPIRY-MON,
     *                                     COBOL 1260-EDIT-EXPIRY-YEAR)
     * Fields: "expMonth" AND "expYear"  |  CARD-EXPIRAION-DATE PIC X(10)
     * BMS: EXPMONI (X(2)) + EXPYEARI (X(4))
     * MIN/MAX_EXPIRY_YEAR = 1950/2099, MIN/MAX_EXPIRY_MONTH = 1/12
     *
     * CRITICAL NULL HANDLING: a null expDate produces TWO FieldError
     * entries — one for "expMonth" and one for "expYear" — each with a
     * Java null reference (NOT the String literal "null") as the
     * rejectedValue. This mirrors COBOL's treatment of EXPMONI and
     * EXPYEARI as independent BMS fields.
     * ================================================================== */

    @Test
    @DisplayName("expDate: null produces TWO FieldError entries ('expMonth' + 'expYear') with null Java references")
    void shouldRejectWhenExpiryDateIsNullWithTwoFieldErrors() {
        CardDto dto = validDto();
        dto.setCardExpDate(null);

        assertThatThrownBy(() -> cardValidator.validateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();

                    // Both expected field names must be present
                    assertThat(errors)
                            .extracting(FieldError::fieldName)
                            .contains("expMonth", "expYear");

                    // The "expMonth" entry: rejectedValue must be a Java null
                    // reference (NOT the literal String "null")
                    assertThat(errors)
                            .filteredOn(e -> "expMonth".equals(e.fieldName()))
                            .hasSize(1)
                            .first()
                            .satisfies(err -> {
                                assertThat(err.rejectedValue()).isNull();
                                assertThat(err.message())
                                        .isEqualTo("Expiration month must be between 1 and 12");
                            });

                    // The "expYear" entry: rejectedValue must be a Java null
                    // reference (NOT the literal String "null")
                    assertThat(errors)
                            .filteredOn(e -> "expYear".equals(e.fieldName()))
                            .hasSize(1)
                            .first()
                            .satisfies(err -> {
                                assertThat(err.rejectedValue()).isNull();
                                assertThat(err.message())
                                        .isEqualTo("Year must be between 1950 and 2099");
                            });
                });
    }

    @Test
    @DisplayName("expDate: valid mid-range date (2030-12-31) is accepted")
    void shouldAcceptValidExpiryDate() {
        CardDto dto = validDto();
        dto.setCardExpDate(LocalDate.of(2030, 12, 31));
        assertThatCode(() -> cardValidator.validateFields(dto)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("expDate: lower-boundary year/month (1950-01-01) is accepted")
    void shouldAcceptExpiryDateAtLowerBoundaryYear() {
        CardDto dto = validDto();
        // MIN_EXPIRY_YEAR=1950, MIN_EXPIRY_MONTH=1 — inclusive boundary
        dto.setCardExpDate(LocalDate.of(1950, 1, 1));
        assertThatCode(() -> cardValidator.validateFields(dto)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("expDate: upper-boundary year/month (2099-12-31) is accepted")
    void shouldAcceptExpiryDateAtUpperBoundaryYear() {
        CardDto dto = validDto();
        // MAX_EXPIRY_YEAR=2099, MAX_EXPIRY_MONTH=12 — inclusive boundary
        dto.setCardExpDate(LocalDate.of(2099, 12, 31));
        assertThatCode(() -> cardValidator.validateFields(dto)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("expDate: year below MIN_EXPIRY_YEAR=1950 produces 'expYear' FieldError with numeric rejectedValue")
    void shouldRejectExpiryYearBelowMinimum() {
        CardDto dto = validDto();
        dto.setCardExpDate(LocalDate.of(1949, 1, 1));

        assertThatThrownBy(() -> cardValidator.validateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors)
                            .filteredOn(e -> "expYear".equals(e.fieldName()))
                            .hasSize(1)
                            .first()
                            .satisfies(err -> {
                                // Out-of-range year is stringified via String.valueOf(int)
                                assertThat(err.rejectedValue()).isEqualTo("1949");
                                assertThat(err.message())
                                        .isEqualTo("Year must be between 1950 and 2099");
                            });
                    // Month 1 is valid — no expMonth error should be emitted
                    assertThat(errors)
                            .filteredOn(e -> "expMonth".equals(e.fieldName()))
                            .isEmpty();
                });
    }

    @Test
    @DisplayName("expDate: year above MAX_EXPIRY_YEAR=2099 produces 'expYear' FieldError")
    void shouldRejectExpiryYearAboveMaximum() {
        CardDto dto = validDto();
        dto.setCardExpDate(LocalDate.of(2100, 1, 1));

        assertThatThrownBy(() -> cardValidator.validateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors)
                            .filteredOn(e -> "expYear".equals(e.fieldName()))
                            .hasSize(1)
                            .first()
                            .satisfies(err -> {
                                assertThat(err.rejectedValue()).isEqualTo("2100");
                                assertThat(err.message())
                                        .isEqualTo("Year must be between 1950 and 2099");
                            });
                    // Month 1 is valid — no expMonth error should be emitted
                    assertThat(errors)
                            .filteredOn(e -> "expMonth".equals(e.fieldName()))
                            .isEmpty();
                });
    }

    /* ====================================================================
     * SECTION 6 — Multiple Error Aggregation (Error-Accumulate Pattern)
     *
     * Verifies that when multiple fields fail validation in a single
     * invocation of validateFields(), ALL failures are collected into a
     * single ValidationException rather than throwing on the first
     * failure. This mirrors COBOL's batch-redisplay workflow where
     * COCRDUPC.cbl marks every failing field on the BMS map with
     * ATTRB=UNDERLINE,BRT before returning control to the user.
     * ================================================================== */

    @Test
    @DisplayName("Multiple invalid fields accumulate into ONE ValidationException with all FieldErrors")
    void shouldAggregateMultipleFieldErrors() {
        CardDto dto = new CardDto();
        dto.setCardAcctId("");             // → acctId error
        dto.setCardNum("ABC");             // → cardNum error (non-numeric)
        dto.setCardEmbossedName("");       // → embossedName error
        dto.setCardActiveStatus("");       // → activeStatus error
        dto.setCardExpDate(null);          // → expMonth + expYear errors (TWO)

        assertThatThrownBy(() -> cardValidator.validateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();

                    // Null expDate produces TWO entries → overall minimum is 6
                    // (4 other fields + expMonth + expYear)
                    assertThat(errors).hasSize(6);

                    // All six expected field names must be present
                    assertThat(errors)
                            .extracting(FieldError::fieldName)
                            .containsExactlyInAnyOrder(
                                    "acctId",
                                    "cardNum",
                                    "embossedName",
                                    "activeStatus",
                                    "expMonth",
                                    "expYear"
                            );
                });
    }

    @Test
    @DisplayName("Exception from multi-error aggregation carries the VALIDATION_ERROR_CODE 'VALID'")
    void shouldCarryValidationErrorCodeWhenAggregatingMultipleErrors() {
        CardDto dto = new CardDto();
        dto.setCardAcctId("");
        dto.setCardNum("ABC");
        dto.setCardEmbossedName("");
        dto.setCardActiveStatus("");
        dto.setCardExpDate(null);

        assertThatThrownBy(() -> cardValidator.validateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    // The (List<FieldError>) constructor sets the error code to
                    // VALIDATION_ERROR_CODE = "VALID" per ValidationException.java
                    assertThat(((ValidationException) ex).getErrorCode())
                            .isEqualTo(ValidationException.VALIDATION_ERROR_CODE);
                });
    }

    /* ====================================================================
     * SECTION 7 — Happy Path (All Fields Valid)
     *
     * Verifies that validateFields() completes normally (does NOT throw)
     * when every field passes its validation rule.
     * ================================================================== */

    @Test
    @DisplayName("Happy path: all fields valid → validateFields returns normally (no exception)")
    void shouldAcceptWhenAllFieldsValid() {
        CardDto dto = validDto();
        assertThatCode(() -> cardValidator.validateFields(dto)).doesNotThrowAnyException();
    }

    /* ====================================================================
     * SECTION 8 — Masking Verification (PCI DSS Safeguards)
     *
     * Verifies that CardValidator masks sensitive identifiers (card number
     * and account ID) in FieldError.rejectedValue to prevent full-PAN
     * exposure in log files and API error payloads. Embossed name and
     * active status are NOT masked (not PII).
     *
     * Mask format: "****" + last MASK_VISIBLE_DIGITS (=4) characters of
     * the input. For inputs of length ≤ 4, the mask is just "****".
     * ================================================================== */

    @Test
    @DisplayName("Invalid 20-digit cardNum is masked to '****' + last 4 digits in rejectedValue")
    void shouldMaskInvalidCardNumberInFieldError() {
        CardDto dto = validDto();
        dto.setCardNum("41111111111111119999"); // 20 digits — exceeds max length

        assertThatThrownBy(() -> cardValidator.validateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors)
                            .filteredOn(e -> "cardNum".equals(e.fieldName()))
                            .first()
                            .satisfies(err -> {
                                // Last 4 visible: "9999"; prefix: "****"
                                // NOTE: prefix is literal "****" (four asterisks),
                                // NOT 16 asterisks — this is the documented format
                                assertThat(err.rejectedValue()).isEqualTo("****9999");
                                assertThat(err.rejectedValue()).doesNotContain("4111");
                                assertThat(err.rejectedValue()).doesNotContain("1111");
                                assertThat(err.rejectedValue()).hasSize(8);
                            });
                });
    }

    @Test
    @DisplayName("Invalid 12-digit acctId is masked to '****' + last 4 digits in rejectedValue")
    void shouldMaskInvalidAccountIdInFieldError() {
        CardDto dto = validDto();
        dto.setCardAcctId("123456789012"); // 12 digits — exceeds ACCT_ID_MAX_LENGTH=11

        assertThatThrownBy(() -> cardValidator.validateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors)
                            .filteredOn(e -> "acctId".equals(e.fieldName()))
                            .first()
                            .satisfies(err -> {
                                // Last 4 visible: "9012"; prefix: "****"
                                assertThat(err.rejectedValue()).isEqualTo("****9012");
                                assertThat(err.rejectedValue()).doesNotContain("12345");
                                assertThat(err.rejectedValue()).hasSize(8);
                            });
                });
    }

    @Test
    @DisplayName("Short invalid acctId (≤ MASK_VISIBLE_DIGITS) is masked to exactly '****' (no digits exposed)")
    void shouldMaskShortAccountIdToOnlyAsterisks() {
        CardDto dto = validDto();
        // "A12" has length 3 ≤ MASK_VISIBLE_DIGITS(4) → mask returns just "****"
        dto.setCardAcctId("A12");

        assertThatThrownBy(() -> cardValidator.validateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors)
                            .filteredOn(e -> "acctId".equals(e.fieldName()))
                            .first()
                            .satisfies(err -> {
                                // For inputs of length <= MASK_VISIBLE_DIGITS,
                                // the masked form is just "****" — no digits surface
                                assertThat(err.rejectedValue()).isEqualTo("****");
                                assertThat(err.message()).isEqualTo("Account number must be numeric");
                            });
                });
    }

    @Test
    @DisplayName("Short invalid cardNum (≤ MASK_VISIBLE_DIGITS) is masked to exactly '****' (no digits exposed)")
    void shouldMaskShortCardNumberToOnlyAsterisks() {
        CardDto dto = validDto();
        // "ABCD" has length 4 = MASK_VISIBLE_DIGITS → mask returns just "****"
        dto.setCardNum("ABCD");

        assertThatThrownBy(() -> cardValidator.validateFields(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors)
                            .filteredOn(e -> "cardNum".equals(e.fieldName()))
                            .first()
                            .satisfies(err -> {
                                assertThat(err.rejectedValue()).isEqualTo("****");
                                assertThat(err.message()).isEqualTo("Card number must be numeric");
                            });
                });
    }

    /* ====================================================================
     * Test fixture helper
     * ================================================================== */

    /**
     * Builds a {@link CardDto} populated with all-valid field values that
     * would pass every rule in {@link CardValidator#validateFields(CardDto)}.
     * Tests typically invoke this helper and then mutate exactly one field
     * to an invalid value to exercise a single validation branch in
     * isolation.
     *
     * <p>Field values mirror the COBOL-equivalent CARD-RECORD canonical
     * values used in {@code CardUpdateServiceTest} to preserve traceability
     * between the service-layer and domain-layer test suites.</p>
     *
     * @return a fresh fully-valid {@link CardDto} with every validated
     *         field populated
     */
    private CardDto validDto() {
        CardDto dto = new CardDto();
        dto.setCardAcctId(VALID_ACCT_ID);
        dto.setCardNum(VALID_CARD_NUM);
        dto.setCardEmbossedName(VALID_EMBOSSED_NAME);
        dto.setCardActiveStatus(VALID_ACTIVE_STATUS);
        dto.setCardExpDate(VALID_EXPIRY_DATE);
        dto.setCardCvvCd(VALID_CVV);
        return dto;
    }
}
