/*
 * AccountValidator.java — Domain-Layer Field Validator for Account Update Operations
 *
 * Migrated from COBOL source artifacts:
 *   - app/cbl/COACTUPC.cbl (online account update — 4,236 lines; paragraphs
 *     1200-EDIT-MAP-INPUTS, 1210-EDIT-ACCOUNT, 1215-EDIT-MANDATORY,
 *     1220-EDIT-YESNO, 1225-EDIT-ALPHA-REQD, 1235-EDIT-ALPHA-OPT,
 *     1245-EDIT-NUM-REQD, 1250-EDIT-SIGNED-9V2, 1260-EDIT-US-PHONE-NUM,
 *     1265-EDIT-US-SSN, 1270-EDIT-US-STATE-CD, 1275-EDIT-FICO-SCORE,
 *     1280-EDIT-US-STATE-ZIP-CD)
 *   - app/cpy/CVACT01Y.cpy (ACCOUNT-RECORD 300-byte VSAM record layout —
 *     ACCT-ID PIC 9(11), ACCT-ACTIVE-STATUS PIC X(01), ACCT-CURR-BAL PIC
 *     S9(10)V99, ACCT-CREDIT-LIMIT PIC S9(10)V99, ACCT-CASH-CREDIT-LIMIT
 *     PIC S9(10)V99, ACCT-OPEN-DATE PIC X(10), ACCT-EXPIRAION-DATE PIC
 *     X(10), ACCT-REISSUE-DATE PIC X(10), ACCT-CURR-CYC-CREDIT PIC
 *     S9(10)V99, ACCT-CURR-CYC-DEBIT PIC S9(10)V99)
 *   - app/cpy/CVCUS01Y.cpy (CUSTOMER-RECORD 500-byte VSAM record layout —
 *     CUST-SSN PIC 9(09), CUST-DOB PIC X(10), CUST-FICO-CREDIT-SCORE PIC
 *     9(03), CUST-FIRST-NAME PIC X(25), CUST-MIDDLE-NAME PIC X(25),
 *     CUST-LAST-NAME PIC X(25), CUST-ADDR-LINE-1 PIC X(50),
 *     CUST-ADDR-STATE-CD PIC X(02), CUST-ADDR-ZIP PIC X(10),
 *     CUST-ADDR-CITY PIC X(50), CUST-ADDR-COUNTRY-CD PIC X(03),
 *     CUST-PHONE-NUM-1 PIC X(15), CUST-PHONE-NUM-2 PIC X(15),
 *     CUST-EFT-ACCOUNT-ID PIC X(10), CUST-PRI-CARD-HOLDER-IND PIC X(01))
 *   - app/cpy/COACTUP.CPY (BMS symbolic map CACTUPAI — account update screen)
 *   - app/cpy/COACTVW.CPY (BMS symbolic map CACTVWAI — account view screen)
 *   - app/cpy/CSLKPCDY.cpy (88-level condition tables for NANPA area codes,
 *     US state codes, and state/ZIP prefix combinations — delegated to
 *     {@link com.cardemo.service.shared.ValidationLookupService})
 *   - app/cbl/CSUTLDTC.cbl + app/cpy/CSUTLDPY.cpy + app/cpy/CSUTLDWY.cpy
 *     (date validation subprogram — delegated to
 *     {@link com.cardemo.service.shared.DateValidationService})
 *
 * This validator encapsulates the account and customer field-level validation
 * cascade originally embedded in {@code AccountUpdateService#validateAccountId}
 * (lines 404–433) and {@code AccountUpdateService#validateUpdateFields}
 * (lines 435–567) along with its 13 private helper methods (lines 573–935)
 * and 3 utility methods (lines 941–979). The original
 * {@code validateAccountId} method used a FIRST-FAIL-THROW pattern — each
 * check throws {@link ValidationException} immediately on the first failure.
 * The original {@code validateUpdateFields} method used an ERROR-AGGREGATION
 * pattern — each of 24 per-field checks appends any failures to a shared
 * {@link java.util.List List} of {@link ValidationException.FieldError
 * FieldError} entries, a cross-field state/ZIP prefix check (step 25) runs
 * only when both individual state and ZIP validations passed, and a single
 * {@link ValidationException} carrying the complete list is raised at the
 * end. Both patterns are preserved verbatim in the extracted validator per
 * AAP Rule R-001 (no business logic rewriting) — every validation check,
 * message string, field key, validation order, and control flow matches
 * the source {@code AccountUpdateService} byte-for-byte (modulo local
 * constant references which now resolve to centralized constant holders).
 *
 * COBOL Paragraph → Java Method Traceability:
 *   COACTUPC.cbl 1200-EDIT-MAP-INPUTS (orchestration)    → validateUpdateFields(AccountDto)
 *   COACTUPC.cbl 1210-EDIT-ACCOUNT (lines 1783-1822)     → validateAccountId(String)
 *   COACTUPC.cbl 1215-EDIT-MANDATORY                     → validateMandatory(...)
 *   COACTUPC.cbl 1220-EDIT-YESNO                         → validateYesNo(...)
 *   COACTUPC.cbl 1225-EDIT-ALPHA-REQD                    → validateAlphaRequired(...)
 *   COACTUPC.cbl 1235-EDIT-ALPHA-OPT                     → validateAlphaOptional(...)
 *   COACTUPC.cbl 1245-EDIT-NUM-REQD                      → validateNumericRequired(...)
 *   COACTUPC.cbl 1250-EDIT-SIGNED-9V2                    → validateMonetaryField(...)
 *   COACTUPC.cbl 1260-EDIT-US-PHONE-NUM                  → validatePhoneNumber(...)
 *   COACTUPC.cbl 1265-EDIT-US-SSN (lines 2431-2491)      → validateSsn(...)
 *   COACTUPC.cbl 1270-EDIT-US-STATE-CD                   → validateStateField(...)
 *   COACTUPC.cbl 1275-EDIT-FICO-SCORE (lines 2514-2533)  → validateFicoScore(...)
 *   COACTUPC.cbl 1280-EDIT-US-STATE-ZIP-CD               → validateUpdateFields step 25
 *   CSUTLDPY.cpy EDIT-DATE-CCYYMMDD                      → validateDateField(...)
 *   CSUTLDPY.cpy EDIT-DATE-OF-BIRTH                      → validateDateOfBirthField(...)
 *
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 * Licensed under the Apache License, Version 2.0
 */
package com.cardemo.domain.validation;

import com.cardemo.domain.constants.AccountConstants;
import com.cardemo.domain.constants.DateFormatConstants;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.AccountDto;
import com.cardemo.service.shared.DateValidationService;
import com.cardemo.service.shared.ValidationLookupService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Account and customer field-level validator — extracted from
 * {@code AccountUpdateService} during the domain-layer refactoring (AAP
 * Section 0.4.1 &quot;Domain Validation Pattern&quot;). Encapsulates the
 * validation logic originally embedded in COBOL program
 * {@code COACTUPC.cbl} (4,236 lines) paragraph
 * {@code 1200-EDIT-MAP-INPUTS} and its sub-paragraphs.
 *
 * <p>This component is framework-independent beyond Spring's
 * {@code @Component} stereotype for dependency injection. It delegates to
 * {@link ValidationLookupService} (CSLKPCDY.cpy tables: NANPA area codes,
 * US state codes, state/ZIP prefix combinations) and to
 * {@link DateValidationService} (CSUTLDTC.cbl + CEEDAYS equivalent
 * CCYYMMDD date validation and date-of-birth not-in-future check).</p>
 *
 * <h3>Validation Patterns</h3>
 * <p>{@link #validateAccountId(String)} uses a <b>first-fail-throw</b>
 * pattern: the first failed check raises {@link ValidationException}
 * immediately, preserving the COBOL 1210-EDIT-ACCOUNT short-circuit
 * semantics (control is never returned to later checks once a failure
 * occurs). This matches the source {@code AccountUpdateService} byte-for-byte.</p>
 *
 * <p>{@link #validateUpdateFields(AccountDto)} uses an
 * <b>error-aggregation</b> pattern: every field check appends any failures
 * to a shared {@code List<FieldError>}, and a single
 * {@link ValidationException} carrying the complete list is raised at the
 * end of the cascade. This matches the COBOL
 * {@code 1200-EDIT-MAP-INPUTS} behavior of marking every failing field on
 * the BMS map with {@code ATTRB=UNDERLINE,BRT} before returning control to
 * the user for a batch-redisplay correction workflow. The cross-field
 * state/ZIP prefix check (step 25) runs only when both individual state
 * and ZIP validations passed, matching COBOL
 * {@code 1280-EDIT-US-STATE-ZIP-CD}'s guard condition.</p>
 *
 * <h3>COBOL Paragraph Mapping</h3>
 * <ul>
 *   <li>1210-EDIT-ACCOUNT → {@link #validateAccountId(String)}</li>
 *   <li>1200-EDIT-MAP-INPUTS → {@link #validateUpdateFields(AccountDto)}</li>
 *   <li>1215-EDIT-MANDATORY / 1220-EDIT-YESNO / 1225-EDIT-ALPHA-REQD /
 *       1235-EDIT-ALPHA-OPT / 1245-EDIT-NUM-REQD / 1250-EDIT-SIGNED-9V2 /
 *       1260-EDIT-US-PHONE-NUM / 1265-EDIT-US-SSN / 1270-EDIT-US-STATE-CD /
 *       1275-EDIT-FICO-SCORE / 1280-EDIT-US-STATE-ZIP-CD →
 *       private field-level helper methods</li>
 * </ul>
 *
 * <h3>Thread Safety</h3>
 * <p>This component is stateless and thread-safe. The two injected
 * dependencies ({@link DateValidationService} and
 * {@link ValidationLookupService}) are also stateless singletons, so
 * concurrent invocations of validator methods are safe.</p>
 *
 * @see AccountDto
 * @see AccountConstants
 * @see DateFormatConstants
 * @see DateValidationService
 * @see ValidationLookupService
 * @see ValidationException
 */
@Component
public class AccountValidator {

    /**
     * SLF4J logger for the account validator. Used by
     * {@link #validateUpdateFields(AccountDto)} to emit a WARN-level entry
     * including the count of aggregated field errors before the combined
     * {@link ValidationException} is thrown. Logging preserves the exact
     * message format used by the source {@code AccountUpdateService} for
     * operational log compatibility.
     */
    private static final Logger logger = LoggerFactory.getLogger(AccountValidator.class);

    /**
     * Shared date validation service — replaces COBOL {@code CSUTLDTC.cbl}
     * subprogram and the LE {@code CEEDAYS} callable service. Invoked by
     * {@link #validateDateField(LocalDate, String, String, List)} and
     * {@link #validateDateOfBirthField(LocalDate, List)} to perform
     * CCYYMMDD format parsing, year/century reasonableness, month validity,
     * day validity, and date-of-birth not-in-future checks.
     */
    private final DateValidationService dateValidationService;

    /**
     * Shared lookup service — replaces COBOL {@code CSLKPCDY.cpy} 88-level
     * condition tables. Invoked by
     * {@link #validatePhoneNumber(String, String, String, List)} for
     * NANPA area code validation, by
     * {@link #validateStateField(String, List)} for US state code
     * validation, and by {@link #validateUpdateFields(AccountDto)} step 25
     * for cross-field state/ZIP prefix validation.
     */
    private final ValidationLookupService validationLookupService;

    /**
     * Constructs the validator with required shared services. Spring
     * resolves both dependencies via constructor-based dependency
     * injection. Both services are stateless and thread-safe singletons.
     *
     * @param dateValidationService   shared date validation service
     *                                (replaces CSUTLDTC.cbl + CEEDAYS)
     * @param validationLookupService shared lookup service (replaces
     *                                CSLKPCDY.cpy 88-level condition tables)
     */
    public AccountValidator(DateValidationService dateValidationService,
                            ValidationLookupService validationLookupService) {
        this.dateValidationService = dateValidationService;
        this.validationLookupService = validationLookupService;
    }

    // =========================================================================
    // Validation Methods
    // =========================================================================

    /**
     * Validates account ID input.
     * Maps COBOL 1210-EDIT-ACCOUNT (lines 1783-1822).
     * Account ID must be an 11-digit non-zero numeric string.
     *
     * @param acctId the account ID to validate
     * @throws ValidationException if the account ID is invalid
     */
    public void validateAccountId(String acctId) {
        if (isBlankOrNull(acctId)) {
            throw ValidationException.of("acctId", acctId,
                    "Account Number must be supplied.");
        }
        String trimmed = acctId.trim();
        if (!trimmed.matches("\\d+")) {
            throw ValidationException.of("acctId", acctId,
                    "Account Number if supplied must be a "
                            + AccountConstants.ACCOUNT_ID_LENGTH + " digit Non-Zero Number");
        }
        if (trimmed.length() != AccountConstants.ACCOUNT_ID_LENGTH) {
            throw ValidationException.of("acctId", acctId,
                    "Account Number if supplied must be a "
                            + AccountConstants.ACCOUNT_ID_LENGTH + " digit Non-Zero Number");
        }
        if (trimmed.chars().allMatch(c -> c == '0')) {
            throw ValidationException.of("acctId", acctId,
                    "Account Number if supplied must be a "
                            + AccountConstants.ACCOUNT_ID_LENGTH + " digit Non-Zero Number");
        }
    }

    /**
     * Comprehensive field validation cascade — MUST match exact COBOL validation order.
     * Maps COBOL COACTUPC 1200-EDIT-MAP-INPUTS (lines 1429-1676).
     *
     * <p>Aggregates ALL validation errors before throwing a single {@link ValidationException},
     * matching the COBOL pattern of setting error flags and checking at the end.</p>
     *
     * <p>Validation order (25+ fields, preserved from COBOL):</p>
     * <ol>
     *   <li>Account Status (Y/N)</li>
     *   <li>Open Date (CCYYMMDD)</li>
     *   <li>Credit Limit (signed decimal)</li>
     *   <li>Expiry Date (CCYYMMDD)</li>
     *   <li>Cash Credit Limit (signed decimal)</li>
     *   <li>Reissue Date (CCYYMMDD)</li>
     *   <li>Current Balance (signed decimal)</li>
     *   <li>Current Cycle Credit (signed decimal)</li>
     *   <li>Current Cycle Debit (signed decimal)</li>
     *   <li>SSN (3-part: invalid 000/666/900-999)</li>
     *   <li>Date of Birth (date + not-in-future)</li>
     *   <li>FICO Score (range 300-850)</li>
     *   <li>First Name (required alpha)</li>
     *   <li>Middle Name (optional alpha)</li>
     *   <li>Last Name (required alpha)</li>
     *   <li>Address Line 1 (mandatory)</li>
     *   <li>State (alpha + state code lookup)</li>
     *   <li>ZIP (numeric required)</li>
     *   <li>City (required alpha)</li>
     *   <li>Country (required alpha)</li>
     *   <li>Phone 1 (optional, NANPA area code)</li>
     *   <li>Phone 2 (optional, NANPA area code)</li>
     *   <li>EFT Account ID (numeric required)</li>
     *   <li>Primary Card Holder (Y/N)</li>
     *   <li>Cross-field: State/ZIP prefix validation</li>
     * </ol>
     *
     * @param dto the AccountDto containing fields to validate
     * @throws ValidationException with aggregated list of all field errors
     */
    public void validateUpdateFields(AccountDto dto) {
        List<ValidationException.FieldError> errors = new ArrayList<>();
        boolean stateValid = false;
        boolean zipValid = false;

        // 1. Account Status (← 1220-EDIT-YESNO for ACUP-ACCT-STATUS)
        validateYesNo(dto.getAcctActiveStatus(), "acctActiveStatus", "Account Status", errors);

        // 2. Open Date (← EDIT-DATE-CCYYMMDD)
        validateDateField(dto.getAcctOpenDate(), "acctOpenDate", "Open Date", errors);

        // 3. Credit Limit (← 1250-EDIT-SIGNED-9V2)
        validateMonetaryField(dto.getAcctCreditLimit(), "acctCreditLimit", "Credit Limit", errors);

        // 4. Expiry Date (← EDIT-DATE-CCYYMMDD)
        validateDateField(dto.getAcctExpDate(), "acctExpDate", "Expiry Date", errors);

        // 5. Cash Credit Limit (← 1250-EDIT-SIGNED-9V2)
        validateMonetaryField(dto.getAcctCashCreditLimit(), "acctCashCreditLimit", "Cash Credit Limit", errors);

        // 6. Reissue Date (← EDIT-DATE-CCYYMMDD)
        validateDateField(dto.getAcctReissueDate(), "acctReissueDate", "Reissue Date", errors);

        // 7. Current Balance (← 1250-EDIT-SIGNED-9V2)
        validateMonetaryField(dto.getAcctCurrBal(), "acctCurrBal", "Current Balance", errors);

        // 8. Current Cycle Credit (← 1250-EDIT-SIGNED-9V2)
        validateMonetaryField(dto.getAcctCurrCycCredit(), "acctCurrCycCredit", "Current Cycle Credit", errors);

        // 9. Current Cycle Debit (← 1250-EDIT-SIGNED-9V2)
        validateMonetaryField(dto.getAcctCurrCycDebit(), "acctCurrCycDebit", "Current Cycle Debit", errors);

        // 10. SSN (← 1265-EDIT-US-SSN, 3-part validation)
        validateSsn(dto.getCustSsn(), errors);

        // 11. Date of Birth (← EDIT-DATE-CCYYMMDD + EDIT-DATE-OF-BIRTH, not-in-future check)
        validateDateOfBirthField(dto.getCustDob(), errors);

        // 12. FICO Score (← 1275-EDIT-FICO-SCORE, range 300-850)
        validateFicoScore(dto.getCustFicoScore(), errors);

        // 13. First Name (← 1225-EDIT-ALPHA-REQD)
        validateAlphaRequired(dto.getCustFname(), "custFname", "First Name", 25, errors);

        // 14. Middle Name (← 1235-EDIT-ALPHA-OPT — optional)
        validateAlphaOptional(dto.getCustMname(), "custMname", "Middle Name", 25, errors);

        // 15. Last Name (← 1225-EDIT-ALPHA-REQD)
        validateAlphaRequired(dto.getCustLname(), "custLname", "Last Name", 25, errors);

        // 16. Address Line 1 (← 1215-EDIT-MANDATORY)
        validateMandatory(dto.getCustAddr1(), "custAddr1", "Address Line 1", errors);

        // 17. State (← 1225-EDIT-ALPHA-REQD + 1270-EDIT-US-STATE-CD)
        stateValid = validateStateField(dto.getCustState(), errors);

        // 18. ZIP (← 1245-EDIT-NUM-REQD, 5 digits)
        zipValid = validateZipField(dto.getCustZip(), errors);

        // 19. City (← 1225-EDIT-ALPHA-REQD)
        validateAlphaRequired(dto.getCustCity(), "custCity", "City", 50, errors);

        // 20. Country (← 1225-EDIT-ALPHA-REQD)
        validateAlphaRequired(dto.getCustCountry(), "custCountry", "Country", 3, errors);

        // 21. Phone 1 (← 1260-EDIT-US-PHONE-NUM — optional, 3-part NANPA)
        validatePhoneNumber(dto.getCustPhone1(), "custPhone1", "Phone 1", errors);

        // 22. Phone 2 (← 1260-EDIT-US-PHONE-NUM — optional, 3-part NANPA)
        validatePhoneNumber(dto.getCustPhone2(), "custPhone2", "Phone 2", errors);

        // 23. EFT Account ID (← 1245-EDIT-NUM-REQD)
        validateNumericRequired(dto.getCustEftAcct(), "custEftAcct", "EFT Account ID", 10, errors);

        // 24. Primary Card Holder Indicator (← 1220-EDIT-YESNO)
        validateYesNo(dto.getCustProfileFlag(), "custProfileFlag", "Primary Card Holder Indicator", errors);

        // 25. Cross-field: State/ZIP prefix (← 1280-EDIT-US-STATE-ZIP-CD)
        // Only validate if BOTH state AND ZIP passed individual validation
        if (stateValid && zipValid
                && !isBlankOrNull(dto.getCustState()) && !isBlankOrNull(dto.getCustZip())) {
            if (!validationLookupService.isValidStateZipPrefix(
                    dto.getCustState().toUpperCase(), dto.getCustZip())) {
                errors.add(new ValidationException.FieldError(
                        "custZip", dto.getCustZip(), "Invalid zip code for state " + dto.getCustState()));
            }
        }

        // Throw aggregated errors if any validation failed
        if (!errors.isEmpty()) {
            logger.warn("Account update validation failed with {} error(s)", errors.size());
            throw new ValidationException(errors);
        }
    }

    // =========================================================================
    // Field-Level Validation Helpers
    // =========================================================================

    /**
     * Validates a yes/no field (must be 'Y' or 'N', case-insensitive).
     * Maps COBOL 1220-EDIT-YESNO paragraph.
     */
    private void validateYesNo(String value, String fieldKey, String fieldLabel,
                               List<ValidationException.FieldError> errors) {
        if (isBlankOrNull(value)) {
            errors.add(new ValidationException.FieldError(fieldKey, value,
                    fieldLabel + " must be supplied."));
            return;
        }
        String upper = value.trim().toUpperCase();
        if (!"Y".equals(upper) && !"N".equals(upper)) {
            errors.add(new ValidationException.FieldError(fieldKey, value,
                    fieldLabel + " must be Y or N."));
        }
    }

    /**
     * Validates a required alphabetic field (letters and spaces only).
     * Maps COBOL 1225-EDIT-ALPHA-REQD paragraph.
     */
    private void validateAlphaRequired(String value, String fieldKey, String fieldLabel,
                                       int maxLength, List<ValidationException.FieldError> errors) {
        if (isBlankOrNull(value)) {
            errors.add(new ValidationException.FieldError(fieldKey, value,
                    fieldLabel + " must be supplied."));
            return;
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            errors.add(new ValidationException.FieldError(fieldKey, value,
                    fieldLabel + " must not exceed " + maxLength + " characters."));
            return;
        }
        if (!trimmed.matches("^[a-zA-Z ]+$")) {
            errors.add(new ValidationException.FieldError(fieldKey, value,
                    fieldLabel + " can have alphabets only."));
        }
    }

    /**
     * Validates an optional alphabetic field (letters and spaces only, if provided).
     * Maps COBOL 1235-EDIT-ALPHA-OPT paragraph.
     * Blank/null is valid for optional fields.
     */
    private void validateAlphaOptional(String value, String fieldKey, String fieldLabel,
                                       int maxLength, List<ValidationException.FieldError> errors) {
        if (isBlankOrNull(value)) {
            return; // Optional field — blank is valid
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            errors.add(new ValidationException.FieldError(fieldKey, value,
                    fieldLabel + " must not exceed " + maxLength + " characters."));
            return;
        }
        if (!trimmed.matches("^[a-zA-Z ]+$")) {
            errors.add(new ValidationException.FieldError(fieldKey, value,
                    fieldLabel + " can have alphabets only."));
        }
    }

    /**
     * Validates a required numeric field (digits only, non-zero, max length).
     * Maps COBOL 1245-EDIT-NUM-REQD paragraph.
     */
    private void validateNumericRequired(String value, String fieldKey, String fieldLabel,
                                         int maxLength, List<ValidationException.FieldError> errors) {
        if (isBlankOrNull(value)) {
            errors.add(new ValidationException.FieldError(fieldKey, value,
                    fieldLabel + " must be supplied."));
            return;
        }
        String trimmed = value.trim();
        if (!trimmed.matches("\\d+")) {
            errors.add(new ValidationException.FieldError(fieldKey, value,
                    fieldLabel + " must be all numeric."));
            return;
        }
        if (trimmed.length() > maxLength) {
            errors.add(new ValidationException.FieldError(fieldKey, value,
                    fieldLabel + " must not exceed " + maxLength + " digits."));
            return;
        }
        if (trimmed.chars().allMatch(c -> c == '0')) {
            errors.add(new ValidationException.FieldError(fieldKey, value,
                    fieldLabel + " must not be zero."));
        }
    }

    /**
     * Validates a mandatory field (any characters, must not be blank).
     * Maps COBOL 1215-EDIT-MANDATORY paragraph.
     */
    private void validateMandatory(String value, String fieldKey, String fieldLabel,
                                   List<ValidationException.FieldError> errors) {
        if (isBlankOrNull(value)) {
            errors.add(new ValidationException.FieldError(fieldKey, value,
                    fieldLabel + " must be supplied."));
        }
    }

    /**
     * Validates a monetary BigDecimal field (must not be null).
     * Maps COBOL 1250-EDIT-SIGNED-9V2 paragraph.
     * In Java, the BigDecimal type already guarantees valid numeric format;
     * validation ensures the field is present.
     */
    private void validateMonetaryField(BigDecimal value, String fieldKey, String fieldLabel,
                                       List<ValidationException.FieldError> errors) {
        if (value == null) {
            errors.add(new ValidationException.FieldError(fieldKey, null,
                    fieldLabel + " must be supplied."));
        }
    }

    /**
     * Validates a US Social Security Number (3-part structure).
     * Maps COBOL 1265-EDIT-US-SSN (lines 2431-2491).
     *
     * <p>SSN structure: Part1(3) + Part2(2) + Part3(4) = 9 digits.</p>
     * <ul>
     *   <li>Part 1: Cannot be 000, 666, or 900-999 (INVALID-SSN-PART1)</li>
     *   <li>Part 2: Cannot be 00 (range 01-99)</li>
     *   <li>Part 3: Cannot be 0000 (range 0001-9999)</li>
     * </ul>
     */
    private void validateSsn(String ssn, List<ValidationException.FieldError> errors) {
        if (isBlankOrNull(ssn)) {
            errors.add(new ValidationException.FieldError("custSsn", ssn,
                    "SSN must be supplied."));
            return;
        }

        // Strip formatting characters (dashes, spaces) for validation
        String digits = stripNonDigits(ssn);

        if (digits.length() != AccountConstants.SSN_LENGTH) {
            errors.add(new ValidationException.FieldError("custSsn", ssn,
                    "SSN must be exactly " + AccountConstants.SSN_LENGTH + " digits."));
            return;
        }

        if (!digits.matches("\\d{9}")) {
            errors.add(new ValidationException.FieldError("custSsn", ssn,
                    "SSN must be all numeric."));
            return;
        }

        // Part 1: first 3 digits — cannot be 000, 666, or 900-999
        String part1 = digits.substring(0, 3);
        int part1Val = Integer.parseInt(part1);
        if (part1Val == 0 || part1Val == 666 || (part1Val >= 900 && part1Val <= 999)) {
            errors.add(new ValidationException.FieldError("custSsn", ssn,
                    "SSN: First 3 chars: should not be 000, 666, or between 900 and 999"));
        }

        // Part 2: middle 2 digits — cannot be 00
        String part2 = digits.substring(3, 5);
        int part2Val = Integer.parseInt(part2);
        if (part2Val == 0) {
            errors.add(new ValidationException.FieldError("custSsn", ssn,
                    "SSN 4th & 5th chars must not be zero."));
        }

        // Part 3: last 4 digits — cannot be 0000
        String part3 = digits.substring(5, 9);
        int part3Val = Integer.parseInt(part3);
        if (part3Val == 0) {
            errors.add(new ValidationException.FieldError("custSsn", ssn,
                    "SSN Last 4 chars must not be zero."));
        }
    }

    /**
     * Validates FICO credit score (numeric, range 300-850 inclusive).
     * Maps COBOL 1275-EDIT-FICO-SCORE (lines 2514-2533).
     */
    private void validateFicoScore(String ficoStr, List<ValidationException.FieldError> errors) {
        if (isBlankOrNull(ficoStr)) {
            errors.add(new ValidationException.FieldError("custFicoScore", ficoStr,
                    "FICO Score must be supplied."));
            return;
        }

        String trimmed = ficoStr.trim();
        if (!trimmed.matches("\\d+")) {
            errors.add(new ValidationException.FieldError("custFicoScore", ficoStr,
                    "FICO Score must be numeric."));
            return;
        }

        int ficoValue;
        try {
            ficoValue = Integer.parseInt(trimmed);
        } catch (NumberFormatException e) {
            errors.add(new ValidationException.FieldError("custFicoScore", ficoStr,
                    "FICO Score is not a valid number."));
            return;
        }

        if (ficoValue < AccountConstants.FICO_MIN || ficoValue > AccountConstants.FICO_MAX) {
            errors.add(new ValidationException.FieldError("custFicoScore", ficoStr,
                    "FICO Score: should be between " + AccountConstants.FICO_MIN
                            + " and " + AccountConstants.FICO_MAX));
        }
    }

    /**
     * Validates a US phone number (optional, 3-part structure with NANPA area code).
     * Maps COBOL 1260-EDIT-US-PHONE-NUM paragraph.
     *
     * <p>Phone format: area(3) + prefix(3) + line(4) = 10 digits.</p>
     * <ul>
     *   <li>Phone is OPTIONAL — blank/null is valid</li>
     *   <li>Area code: 3 digits, non-zero, validated against NANPA table</li>
     *   <li>Prefix: 3 digits, non-zero</li>
     *   <li>Line number: 4 digits, non-zero</li>
     * </ul>
     */
    private void validatePhoneNumber(String phone, String fieldKey, String fieldLabel,
                                     List<ValidationException.FieldError> errors) {
        if (isBlankOrNull(phone)) {
            return; // Phone is optional — blank is valid
        }

        // Strip formatting characters to extract raw digits
        String digits = stripNonDigits(phone);

        if (digits.isEmpty()) {
            return; // All non-digit characters (treated as blank)
        }

        if (digits.length() != AccountConstants.PHONE_DIGITS_LENGTH) {
            errors.add(new ValidationException.FieldError(fieldKey, phone,
                    fieldLabel + " must be a 10-digit phone number."));
            return;
        }

        // Area code: first 3 digits
        String areaCode = digits.substring(0, 3);
        int areaVal = Integer.parseInt(areaCode);
        if (areaVal == 0) {
            errors.add(new ValidationException.FieldError(fieldKey, phone,
                    fieldLabel + " area code must not be zero."));
        } else if (!validationLookupService.isValidAreaCode(areaCode)) {
            errors.add(new ValidationException.FieldError(fieldKey, phone,
                    fieldLabel + ": Not valid North America general purpose area code"));
        }

        // Prefix: middle 3 digits — numeric, non-zero
        String prefix = digits.substring(3, 6);
        int prefixVal = Integer.parseInt(prefix);
        if (prefixVal == 0) {
            errors.add(new ValidationException.FieldError(fieldKey, phone,
                    fieldLabel + " prefix must not be zero."));
        }

        // Line number: last 4 digits — numeric, non-zero
        String lineNum = digits.substring(6, 10);
        int lineVal = Integer.parseInt(lineNum);
        if (lineVal == 0) {
            errors.add(new ValidationException.FieldError(fieldKey, phone,
                    fieldLabel + " line number must not be zero."));
        }
    }

    /**
     * Validates a date field by formatting LocalDate to CCYYMMDD and delegating
     * to {@link DateValidationService#validateDate(String, String)}.
     * Maps COBOL EDIT-DATE-CCYYMMDD paragraph.
     */
    private void validateDateField(LocalDate date, String fieldKey, String fieldLabel,
                                   List<ValidationException.FieldError> errors) {
        if (date == null) {
            errors.add(new ValidationException.FieldError(fieldKey, null,
                    fieldLabel + " must be supplied."));
            return;
        }
        String dateStr = formatDate(date);
        DateValidationService.DateValidationResult result =
                dateValidationService.validateDate(dateStr, fieldLabel);
        if (!result.valid()) {
            errors.add(new ValidationException.FieldError(fieldKey, dateStr, result.fullMessage()));
        }
    }

    /**
     * Validates date of birth: date format plus not-in-future check.
     * Maps COBOL EDIT-DATE-CCYYMMDD + EDIT-DATE-OF-BIRTH paragraphs.
     */
    private void validateDateOfBirthField(LocalDate dob, List<ValidationException.FieldError> errors) {
        if (dob == null) {
            errors.add(new ValidationException.FieldError("custDob", null,
                    "Date of Birth must be supplied."));
            return;
        }
        String dateStr = formatDate(dob);
        DateValidationService.DateValidationResult result =
                dateValidationService.validateDateOfBirth(dateStr, "Date of Birth");
        if (!result.valid()) {
            errors.add(new ValidationException.FieldError("custDob", dateStr, result.fullMessage()));
        }
    }

    /**
     * Validates state code: required alphabetic field + lookup against CSLKPCDY state table.
     * Maps COBOL 1225-EDIT-ALPHA-REQD + 1270-EDIT-US-STATE-CD paragraphs.
     *
     * @return true if state is individually valid (used for cross-field validation)
     */
    private boolean validateStateField(String state, List<ValidationException.FieldError> errors) {
        if (isBlankOrNull(state)) {
            errors.add(new ValidationException.FieldError("custState", state,
                    "State must be supplied."));
            return false;
        }
        String trimmed = state.trim();
        if (!trimmed.matches("^[a-zA-Z]+$")) {
            errors.add(new ValidationException.FieldError("custState", state,
                    "State can have alphabets only."));
            return false;
        }
        if (!validationLookupService.isValidStateCode(trimmed.toUpperCase())) {
            errors.add(new ValidationException.FieldError("custState", state,
                    "State: is not a valid state code"));
            return false;
        }
        return true;
    }

    /**
     * Validates ZIP code: required, 5-digit or ZIP+4 format, non-zero base.
     * Maps COBOL 1245-EDIT-NUM-REQD for ZIP field.
     *
     * <p>Accepts both standard 5-digit ZIP codes (e.g., "48251") and ZIP+4 format
     * (e.g., "48251-1698"). The COBOL seed data contains ZIP+4 values in the
     * CUSTDATA VSAM dataset, so the validation must accept both formats to
     * maintain behavioral parity with the original application.</p>
     *
     * @return true if ZIP is individually valid (used for cross-field validation)
     */
    private boolean validateZipField(String zip, List<ValidationException.FieldError> errors) {
        if (isBlankOrNull(zip)) {
            errors.add(new ValidationException.FieldError("custZip", zip,
                    "ZIP Code must be supplied."));
            return false;
        }
        String trimmed = zip.trim();
        // Accept 5-digit ZIP (^\d{5}$) or ZIP+4 format (^\d{5}-\d{4}$)
        if (!trimmed.matches("\\d{5}(-\\d{4})?")) {
            errors.add(new ValidationException.FieldError("custZip", zip,
                    "ZIP Code must be 5 digits or ZIP+4 format (e.g., 12345 or 12345-6789)."));
            return false;
        }
        // Validate the 5-digit base portion is not all zeros
        if (trimmed.substring(0, 5).chars().allMatch(c -> c == '0')) {
            errors.add(new ValidationException.FieldError("custZip", zip,
                    "ZIP Code must not be zero."));
            return false;
        }
        return true;
    }

    // =========================================================================
    // Utility Methods
    // =========================================================================

    /**
     * Formats a LocalDate to CCYYMMDD string for DateValidationService.
     * Maps COBOL PIC X(10) CCYYMMDD date string format.
     *
     * @param date the date to format
     * @return CCYYMMDD string representation, or null if date is null
     */
    private String formatDate(LocalDate date) {
        if (date == null) {
            return null;
        }
        return date.format(DateFormatConstants.CCYYMMDD_FORMATTER);
    }

    /**
     * Strips all non-digit characters from a string.
     * Used to normalize phone numbers and SSNs that may contain
     * formatting characters (dashes, parentheses, spaces).
     *
     * @param value the input string
     * @return string containing only digit characters
     */
    private String stripNonDigits(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("[^0-9]", "");
    }

    /**
     * Checks if a string is null, empty, or contains only whitespace.
     * Maps COBOL SPACES / LOW-VALUES sentinel check.
     *
     * @param value the string to check
     * @return true if the value is effectively blank
     */
    private boolean isBlankOrNull(String value) {
        return value == null || value.trim().isEmpty();
    }
}
