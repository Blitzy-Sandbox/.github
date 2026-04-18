/*
 * CardValidator.java — Domain-Layer Field Validator for Card Update Operations
 *
 * Migrated from COBOL source artifacts:
 *   - app/cbl/COCRDUPC.cbl (online card update — 1,560 lines; paragraphs
 *     1210-EDIT-ACCOUNT, 1220-EDIT-CARD, 1230-EDIT-NAME, 1240-EDIT-CARDSTATUS,
 *     1250-EDIT-EXPIRY-MON, 1260-EDIT-EXPIRY-YEAR)
 *   - app/cpy/CVACT02Y.cpy (CARD-RECORD 150-byte VSAM record layout —
 *     CARD-NUM PIC X(16), CARD-ACCT-ID PIC 9(11), CARD-EMBOSSED-NAME PIC X(50),
 *     CARD-EXPIRAION-DATE PIC X(10), CARD-ACTIVE-STATUS PIC X(01))
 *   - app/cpy/COCRDUP.CPY (BMS symbolic map CCRDUPAI — ACCTSIDI, CARDSIDI,
 *     CRDNAMEI, CRDSTCDI, EXPMONI, EXPDAYI, EXPYEARI)
 *
 * This validator encapsulates the card field-level validation cascade
 * originally embedded in {@code CardUpdateService#validateFields(CardDto)}
 * (lines 250–272) and its five private helper methods (lines 278–372).
 * The original method used an ERROR-AGGREGATION pattern — each field check
 * appends a {@code FieldError} to a shared list, and a single
 * {@code ValidationException} carrying the full list is thrown at the end
 * after all fields have been evaluated. This matches the COBOL behavior of
 * setting error flags for ALL invalid fields before returning control to
 * the BMS map for redisplay (COCRDUPC.cbl marks every failing field with
 * {@code ATTRB=UNDERLINE,BRT} rather than short-circuiting on the first
 * failure). This pattern is preserved verbatim in the extracted validator
 * per AAP Rule R-001 (no business logic rewriting).
 *
 * COBOL Paragraph → Java Method Traceability:
 *   COCRDUPC.cbl 1200-EDIT-MAP-INPUTS (orchestration)    → validateFields(CardDto)
 *   COCRDUPC.cbl 1210-EDIT-ACCOUNT (lines ~721–755)      → validateAccountId(...)
 *   COCRDUPC.cbl 1220-EDIT-CARD (lines ~762–799)         → validateCardNumber(...)
 *   COCRDUPC.cbl 1230-EDIT-NAME (lines ~806–839)         → validateEmbossedName(...)
 *   COCRDUPC.cbl 1240-EDIT-CARDSTATUS (lines ~845–872)   → validateActiveStatus(...)
 *   COCRDUPC.cbl 1250-EDIT-EXPIRY-MON (lines ~877–907)   → validateExpiryDate(...)
 *   COCRDUPC.cbl 1260-EDIT-EXPIRY-YEAR (lines ~913–943)  → validateExpiryDate(...)
 *
 * WORKING-STORAGE / BMS Symbolic Map Mappings:
 *   CARD-ACCT-ID         (PIC 9(11))  ← ACCTSIDI OF CCRDUPAI → cardAcctId
 *   CARD-NUM             (PIC X(16))  ← CARDSIDI OF CCRDUPAI → cardNum
 *   CARD-EMBOSSED-NAME   (PIC X(50))  ← CRDNAMEI OF CCRDUPAI → cardEmbossedName
 *   CARD-ACTIVE-STATUS   (PIC X(01))  ← CRDSTCDI OF CCRDUPAI → cardActiveStatus
 *   CARD-EXPIRAION-DATE  (PIC X(10))  ← EXPMONI+EXPDAYI+EXPYEARI → cardExpDate
 *
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 * Licensed under the Apache License, Version 2.0
 */
package com.cardemo.domain.validation;

import com.cardemo.domain.constants.CardConstants;
import com.cardemo.domain.constants.ValidationPatterns;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.CardDto;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Card field-level validator — extracted from
 * {@code CardUpdateService#validateFields(CardDto)} during the domain-layer
 * refactoring (AAP Section 0.4.1 &quot;Domain Validation Pattern&quot;).
 * Encapsulates the 5-field validation cascade (account ID, card number,
 * embossed name, active status, expiry date) originally migrated from COBOL
 * program {@code COCRDUPC.cbl}.
 *
 * <p>This validator uses an <b>error-aggregation</b> pattern (NOT
 * throw-on-first-failure): every field check appends any failures to a shared
 * {@link java.util.List List} of {@link ValidationException.FieldError
 * FieldError} entries, and a single {@link ValidationException} carrying the
 * complete list is raised at the end of the cascade. This is a deliberate
 * divergence from {@link UserValidator} (which throws on first failure):
 * {@code COCRDUPC.cbl} marks every failing field on the BMS map with
 * {@code ATTRB=UNDERLINE,BRT} before returning control to the user for a
 * batch-redisplay correction workflow, so the Java equivalent must surface
 * every failure in one round-trip.</p>
 *
 * <p>This component is framework-independent beyond Spring's
 * {@link Component @Component} stereotype for dependency injection. It holds
 * no mutable state and performs no I/O.</p>
 *
 * <p>All numeric constants (field lengths, expiry year/month ranges, masking
 * digit count) are sourced from {@link CardConstants}; regex patterns come
 * from {@link ValidationPatterns}.</p>
 *
 * <h3>Validation Order</h3>
 * <p>Matches the CICS screen field sequence from {@code COCRDUPC.cbl}
 * paragraph {@code 1200-EDIT-MAP-INPUTS}:</p>
 * <ol>
 *   <li>Account ID (ACCTSIDI / CARD-ACCT-ID) — paragraph 1210-EDIT-ACCOUNT</li>
 *   <li>Card Number (CARDSIDI / CARD-NUM) — paragraph 1220-EDIT-CARD</li>
 *   <li>Embossed Name (CRDNAMEI / CARD-EMBOSSED-NAME) — paragraph 1230-EDIT-NAME</li>
 *   <li>Active Status (CRDSTCDI / CARD-ACTIVE-STATUS) — paragraph 1240-EDIT-CARDSTATUS</li>
 *   <li>Expiry Date (EXPMONI+EXPDAYI+EXPYEARI / CARD-EXPIRAION-DATE) —
 *       paragraphs 1250-EDIT-EXPIRY-MON and 1260-EDIT-EXPIRY-YEAR</li>
 * </ol>
 *
 * <h3>Thread Safety</h3>
 * <p>This class is stateless (holds only an immutable {@link Logger}
 * reference). The Pattern instances referenced from
 * {@link ValidationPatterns} are thread-safe by the {@code Pattern} contract.
 * Matchers are created per-invocation via
 * {@link java.util.regex.Pattern#matcher(CharSequence)} and are therefore
 * not shared across threads. The validator is safe to share as a Spring
 * singleton bean across all request-handling threads.</p>
 *
 * <h3>PCI Compliance Note</h3>
 * <p>Card numbers and account IDs appearing in {@link ValidationException}
 * {@code rejectedValue} fields are masked via {@link #maskCardNumber(String)}
 * and {@link #maskAccountId(String)} to show only the last
 * {@link CardConstants#MASK_VISIBLE_DIGITS} digits before being logged or
 * included in API error responses. This prevents full-PAN exposure in log
 * files and response payloads per PCI DSS requirements.</p>
 *
 * <h3>Consuming Services (post-refactoring)</h3>
 * <ul>
 *   <li>{@code com.cardemo.service.card.CardUpdateService} &rarr;
 *       {@link #validateFields(CardDto)}</li>
 * </ul>
 *
 * @see CardDto
 * @see CardConstants
 * @see ValidationPatterns
 * @see ValidationException
 */
@Component
public class CardValidator {

    /**
     * SLF4J logger bound to {@link CardValidator} for validation-path tracing.
     * Log events propagate the MDC correlation ID set by
     * {@code CorrelationIdFilter} for end-to-end request tracing across
     * controller, service, and domain layers.
     */
    private static final Logger logger = LoggerFactory.getLogger(CardValidator.class);

    /**
     * Default constructor — this validator has no Spring-managed dependencies.
     *
     * <p>The Spring {@code ApplicationContext} instantiates a single bean via
     * the {@link Component @Component} stereotype and injects it into
     * {@code CardUpdateService} after the domain-layer refactoring. No
     * collaborators are required because all validation rules are expressed
     * in terms of static constants and precompiled regex patterns.</p>
     */
    public CardValidator() {
        // Intentionally empty — no collaborators to wire.
    }

    /**
     * Validates all user-editable card fields by delegating to individual
     * field-level validators. Accumulates all failures into a single
     * {@link ValidationException} to support batch display on the CCRDUP
     * BMS screen.
     *
     * <p>Validation order matches the CICS screen field sequence from
     * {@code COCRDUPC.cbl} paragraph {@code 1200-EDIT-MAP-INPUTS}:</p>
     * <ol>
     *   <li>Account ID (ACCTSIDI / CARD-ACCT-ID) — paragraph 1210-EDIT-ACCOUNT</li>
     *   <li>Card Number (CARDSIDI / CARD-NUM) — paragraph 1220-EDIT-CARD</li>
     *   <li>Embossed Name (CRDNAMEI / CARD-EMBOSSED-NAME) — paragraph 1230-EDIT-NAME</li>
     *   <li>Active Status (CRDSTCDI / CARD-ACTIVE-STATUS) — paragraph 1240-EDIT-CARDSTATUS</li>
     *   <li>Expiry Date (EXPMONI+EXPDAYI+EXPYEARI / CARD-EXPIRAION-DATE) —
     *       paragraphs 1250-EDIT-EXPIRY-MON and 1260-EDIT-EXPIRY-YEAR</li>
     * </ol>
     *
     * <p>The resulting {@link ValidationException} carries the aggregated
     * list of {@link ValidationException.FieldError} entries. The error-code
     * is set to {@link ValidationException#VALIDATION_ERROR_CODE} ("VALID")
     * by the {@link ValidationException#ValidationException(List)}
     * constructor, producing a structured error-code + field-errors payload
     * for {@code @ControllerAdvice}-based REST error mapping.</p>
     *
     * @param dto the card DTO carrying the candidate new field values; must
     *            not be {@code null} (a {@code NullPointerException} on
     *            {@code dto.getCardAcctId()} would otherwise surface and
     *            is handled at the service boundary)
     * @throws ValidationException with one or more
     *         {@link ValidationException.FieldError} entries if any field
     *         fails validation; carries error code "VALID"
     */
    public void validateFields(CardDto dto) {
        List<ValidationException.FieldError> errors = new ArrayList<>();

        // Account ID validation (COBOL 1210-EDIT-ACCOUNT, lines ~721–755)
        validateAccountId(dto.getCardAcctId(), errors);

        // Card Number validation (COBOL 1220-EDIT-CARD, lines ~762–799)
        validateCardNumber(dto.getCardNum(), errors);

        // Embossed Name validation (COBOL 1230-EDIT-NAME, lines ~806–839)
        validateEmbossedName(dto.getCardEmbossedName(), errors);

        // Active Status validation (COBOL 1240-EDIT-CARDSTATUS, lines ~845–872)
        validateActiveStatus(dto.getCardActiveStatus(), errors);

        // Expiry Date validation (COBOL 1250/1260-EDIT-EXPIRY-MON/YEAR, lines ~877–943)
        validateExpiryDate(dto.getCardExpDate(), errors);

        if (!errors.isEmpty()) {
            logger.warn("Validation failed for card update: {} error(s)", errors.size());
            throw new ValidationException(errors);
        }
    }

    /**
     * Validates the account ID field — maps COBOL 1210-EDIT-ACCOUNT.
     * Account ID must be non-blank, numeric, and at most
     * {@link CardConstants#ACCT_ID_MAX_LENGTH} digits.
     *
     * <p>Failure cases (in the early-return order of the source program):</p>
     * <ul>
     *   <li>{@code null} or blank → "Account number cannot be blank" — the
     *       rejected value is passed through unmodified (safe: either
     *       {@code null} or whitespace-only)</li>
     *   <li>Non-numeric → "Account number must be numeric" — the rejected
     *       value is masked via {@link #maskAccountId(String)} to avoid
     *       surfacing potentially-sensitive raw input in error payloads</li>
     *   <li>Length exceeds {@link CardConstants#ACCT_ID_MAX_LENGTH} →
     *       "Account number must not exceed N digits" — also masked</li>
     * </ul>
     *
     * @param acctId the candidate account ID (may be {@code null})
     * @param errors the accumulator list to which any
     *               {@link ValidationException.FieldError} for this field
     *               is appended
     */
    private void validateAccountId(String acctId, List<ValidationException.FieldError> errors) {
        if (acctId == null || acctId.isBlank()) {
            errors.add(new ValidationException.FieldError("acctId", acctId,
                    "Account number cannot be blank"));
            return;
        }
        if (!ValidationPatterns.NUMERIC_PATTERN.matcher(acctId).matches()) {
            errors.add(new ValidationException.FieldError("acctId", maskAccountId(acctId),
                    "Account number must be numeric"));
            return;
        }
        if (acctId.length() > CardConstants.ACCT_ID_MAX_LENGTH) {
            errors.add(new ValidationException.FieldError("acctId", maskAccountId(acctId),
                    "Account number must not exceed " + CardConstants.ACCT_ID_MAX_LENGTH
                            + " digits"));
        }
    }

    /**
     * Validates the card number field — maps COBOL 1220-EDIT-CARD.
     * Card number must be non-blank, numeric, and at most
     * {@link CardConstants#CARD_NUM_MAX_LENGTH} digits.
     *
     * <p>Failure cases (in the early-return order of the source program):</p>
     * <ul>
     *   <li>{@code null} or blank → "Card number cannot be blank" — the
     *       rejected value is passed through unmodified (safe: either
     *       {@code null} or whitespace-only)</li>
     *   <li>Non-numeric → "Card number must be numeric" — the rejected
     *       value is masked via {@link #maskCardNumber(String)} to avoid
     *       exposing full PAN in log files or error payloads per PCI DSS</li>
     *   <li>Length exceeds {@link CardConstants#CARD_NUM_MAX_LENGTH} →
     *       "Card number must not exceed N digits" — also masked</li>
     * </ul>
     *
     * @param cardNum the candidate card number (may be {@code null})
     * @param errors  the accumulator list to which any
     *                {@link ValidationException.FieldError} for this field
     *                is appended
     */
    private void validateCardNumber(String cardNum, List<ValidationException.FieldError> errors) {
        if (cardNum == null || cardNum.isBlank()) {
            errors.add(new ValidationException.FieldError("cardNum", cardNum,
                    "Card number cannot be blank"));
            return;
        }
        if (!ValidationPatterns.NUMERIC_PATTERN.matcher(cardNum).matches()) {
            errors.add(new ValidationException.FieldError("cardNum", maskCardNumber(cardNum),
                    "Card number must be numeric"));
            return;
        }
        if (cardNum.length() > CardConstants.CARD_NUM_MAX_LENGTH) {
            errors.add(new ValidationException.FieldError("cardNum", maskCardNumber(cardNum),
                    "Card number must not exceed " + CardConstants.CARD_NUM_MAX_LENGTH
                            + " digits"));
        }
    }

    /**
     * Validates the embossed name field — maps COBOL 1230-EDIT-NAME.
     * Name must contain only alphabets {@code [A-Za-z]} and spaces. The COBOL
     * uses {@code INSPECT CONVERTING} to replace all alpha chars with spaces,
     * then checks if the trimmed result is empty (meaning all chars were
     * alpha/space).
     *
     * <p>Both the blank-input and pattern-mismatch branches produce the
     * SAME error message "Name must contain only letters and spaces" — this
     * preserves exact COBOL parity where {@code WS-MESSAGE} is overwritten
     * with the same "Please check name" literal for either failure mode in
     * paragraph 1230-EDIT-NAME.</p>
     *
     * @param name   the candidate embossed cardholder name (may be {@code null})
     * @param errors the accumulator list to which any
     *               {@link ValidationException.FieldError} for this field
     *               is appended
     */
    private void validateEmbossedName(String name, List<ValidationException.FieldError> errors) {
        if (name == null || name.isBlank()) {
            errors.add(new ValidationException.FieldError("embossedName", name,
                    "Name must contain only letters and spaces"));
            return;
        }
        if (!ValidationPatterns.ALPHA_SPACE_PATTERN.matcher(name).matches()) {
            errors.add(new ValidationException.FieldError("embossedName", name,
                    "Name must contain only letters and spaces"));
        }
    }

    /**
     * Validates the active status field — maps COBOL 1240-EDIT-CARDSTATUS.
     * Status must be exactly "Y" or "N" (case-insensitive comparison per
     * COBOL).
     *
     * <p>The source program performs a {@code MOVE FUNCTION UPPER-CASE(...)
     * TO WS-UPPER} before comparing against the literal {@code 'Y'} /
     * {@code 'N'} — the Java equivalent calls {@link String#toUpperCase()}
     * and then uses {@code "Y".equals(upperStatus)} / {@code "N".equals(upperStatus)}
     * (as opposed to {@link String#equalsIgnoreCase(String)}) to match
     * byte-for-byte the semantics of the source service implementation
     * preserved under AAP Rule R-001.</p>
     *
     * <p>Both the blank-input and invalid-character branches produce the
     * SAME error message "Card status must be Y or N" — this preserves
     * exact COBOL parity where {@code WS-MESSAGE} is overwritten with the
     * same literal for either failure mode.</p>
     *
     * @param status the candidate active status flag (may be {@code null})
     * @param errors the accumulator list to which any
     *               {@link ValidationException.FieldError} for this field
     *               is appended
     */
    private void validateActiveStatus(String status, List<ValidationException.FieldError> errors) {
        if (status == null || status.isBlank()) {
            errors.add(new ValidationException.FieldError("activeStatus", status,
                    "Card status must be Y or N"));
            return;
        }
        String upperStatus = status.toUpperCase();
        if (!"Y".equals(upperStatus) && !"N".equals(upperStatus)) {
            errors.add(new ValidationException.FieldError("activeStatus", status,
                    "Card status must be Y or N"));
        }
    }

    /**
     * Validates the expiration date field — maps COBOL 1250-EDIT-EXPIRY-MON
     * and 1260-EDIT-EXPIRY-YEAR. Month must be
     * {@link CardConstants#MIN_EXPIRY_MONTH}–{@link CardConstants#MAX_EXPIRY_MONTH},
     * year must be {@link CardConstants#MIN_EXPIRY_YEAR}–{@link CardConstants#MAX_EXPIRY_YEAR}.
     * Day is always defaulted to 1 per COBOL convention (not user-editable).
     *
     * <p><b>Null-date behavior (COBOL parity, CRITICAL):</b> when
     * {@code expDate} is {@code null}, TWO {@link ValidationException.FieldError}
     * entries are produced — one for {@code "expMonth"} and one for
     * {@code "expYear"} — each with a {@code null} {@code rejectedValue}
     * (the Java {@code null} reference, not the literal String {@code "null"}).
     * This mirrors COBOL's behavior where EXPMONI and EXPYEARI are
     * INDEPENDENT screen fields in COCRDUPC.cbl: if both are blank/
     * LOW-VALUES, paragraphs 1250-EDIT-EXPIRY-MON and 1260-EDIT-EXPIRY-YEAR
     * EACH set their own {@code WS-ERROR-FLG} and append their own message.
     * The Java validator preserves this semantics by producing two
     * independent {@code FieldError} entries keyed by the separate BMS
     * field names.</p>
     *
     * @param expDate the candidate expiration date (may be {@code null})
     * @param errors  the accumulator list to which any
     *                {@link ValidationException.FieldError} for this field
     *                is appended
     */
    private void validateExpiryDate(LocalDate expDate, List<ValidationException.FieldError> errors) {
        if (expDate == null) {
            errors.add(new ValidationException.FieldError("expMonth", null,
                    "Expiration month must be between 1 and 12"));
            errors.add(new ValidationException.FieldError("expYear", null,
                    "Year must be between 1950 and 2099"));
            return;
        }
        int month = expDate.getMonthValue();
        int year = expDate.getYear();
        if (month < CardConstants.MIN_EXPIRY_MONTH || month > CardConstants.MAX_EXPIRY_MONTH) {
            errors.add(new ValidationException.FieldError("expMonth", String.valueOf(month),
                    "Expiration month must be between 1 and 12"));
        }
        if (year < CardConstants.MIN_EXPIRY_YEAR || year > CardConstants.MAX_EXPIRY_YEAR) {
            errors.add(new ValidationException.FieldError("expYear", String.valueOf(year),
                    "Year must be between 1950 and 2099"));
        }
    }

    /**
     * Masks a card number for safe logging — shows only last 4 digits.
     * Prevents PII exposure in log files per PCI DSS security requirements.
     *
     * <p>The mask format uses a fixed {@code "****"} prefix (four asterisks)
     * concatenated with the trailing {@link CardConstants#MASK_VISIBLE_DIGITS}
     * characters of the input. For inputs of length
     * &le; {@link CardConstants#MASK_VISIBLE_DIGITS} (including {@code null}),
     * the full masked sentinel {@code "****"} is returned without exposing
     * any digits. This format preserves byte-for-byte parity with the source
     * {@code CardUpdateService#maskCardNumber(String)} implementation.</p>
     *
     * @param cardNum raw card number (may be {@code null})
     * @return a masked representation safe for audit logs and error responses
     */
    private String maskCardNumber(String cardNum) {
        if (cardNum == null || cardNum.length() <= CardConstants.MASK_VISIBLE_DIGITS) {
            return "****";
        }
        return "****" + cardNum.substring(cardNum.length() - CardConstants.MASK_VISIBLE_DIGITS);
    }

    /**
     * Masks an account ID for safe logging — shows only last 4 digits.
     * Prevents PII exposure in log files per security requirements.
     *
     * <p>Follows the identical mask format as {@link #maskCardNumber(String)}:
     * a fixed {@code "****"} prefix (four asterisks) concatenated with the
     * trailing {@link CardConstants#MASK_VISIBLE_DIGITS} characters of the
     * input, with {@code "****"} returned for inputs of length
     * &le; {@link CardConstants#MASK_VISIBLE_DIGITS} (including {@code null}).
     * This format preserves byte-for-byte parity with the source
     * {@code CardUpdateService#maskAccountId(String)} implementation.</p>
     *
     * @param acctId raw account ID (may be {@code null})
     * @return a masked representation safe for audit logs and error responses
     */
    private String maskAccountId(String acctId) {
        if (acctId == null || acctId.length() <= CardConstants.MASK_VISIBLE_DIGITS) {
            return "****";
        }
        return "****" + acctId.substring(acctId.length() - CardConstants.MASK_VISIBLE_DIGITS);
    }
}
