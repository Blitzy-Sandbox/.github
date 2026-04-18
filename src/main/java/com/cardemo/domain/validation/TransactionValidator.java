/*
 * TransactionValidator.java — Domain-Layer Field Validator for Transaction Add Operations
 *
 * Migrated from COBOL source artifacts:
 *   - app/cbl/COTRN02C.cbl (online transaction add — 783 lines; paragraphs
 *     VALIDATE-INPUT-DATA-FIELDS, 1200-EDIT-MAP-DATA, EDIT-DATE-CCYY-MM-DD)
 *   - app/cpy/CVTRA05Y.cpy (TRAN-RECORD 350-byte VSAM record layout —
 *     TRAN-ID PIC 9(16), TRAN-TYPE-CD PIC 9(02), TRAN-CAT-CD PIC 9(04),
 *     TRAN-SOURCE PIC X(10), TRAN-DESC PIC X(100), TRAN-AMT PIC S9(09)V99 COMP-3,
 *     TRAN-MERCHANT-ID PIC 9(09), TRAN-MERCHANT-NAME PIC X(50),
 *     TRAN-MERCHANT-CITY PIC X(50), TRAN-MERCHANT-ZIP PIC X(10),
 *     TRAN-ORIG-TS PIC X(26), TRAN-PROC-TS PIC X(26))
 *   - app/cpy/CVACT03Y.cpy (CARD-XREF-RECORD cross-reference layout)
 *   - app/cpy/COCOM01Y.cpy (COMMAREA layout)
 *   - app/cpy/CSUTLDPY.cpy (date validation subprogram procedure division)
 *   - app/cpy/CSUTLDWY.cpy (date validation working-storage section)
 *   - app/cpy/COTRN02.CPY (BMS symbolic map — TTYPCDI, TCATCDI, TRNSRCI,
 *     TDESCI, TORIGDTI, TPROCDTI, MIDI, MNAMEI, MCITYI, MZIPI)
 *
 * This validator encapsulates the 11-field transaction data validation cascade
 * originally embedded in {@code TransactionAddService#validateDataFields(TransactionDto)}
 * (lines 375–499) and its two private helper methods {@code isBlank} (lines
 * 696–698) and {@code isNumeric} (lines 710–720). The original method used an
 * ERROR-AGGREGATION pattern — each field check appends a {@link String} message
 * to a shared list, and a single {@link ValidationException} carrying the
 * semicolon-joined message is thrown at the end after all fields have been
 * evaluated. This matches the COBOL behavior of setting {@code WS-ERR-FLG} but
 * continuing processing through all checks before reporting (COTRN02C.cbl
 * marks every failing field rather than short-circuiting on the first failure).
 * This pattern is preserved VERBATIM in the extracted validator per AAP Rule
 * R-001 (no business logic rewriting).
 *
 * COBOL Paragraph → Java Method Traceability:
 *   COTRN02C.cbl VALIDATE-INPUT-DATA-FIELDS (orchestration)     → validateDataFields(TransactionDto)
 *   COTRN02C.cbl IF TTYPCDI test (lines 333-345)                → Type Code block
 *   COTRN02C.cbl IF TCATCDI test (lines 347-362)                → Category Code block
 *   COTRN02C.cbl IF TRNSRCI test (lines 364-373)                → Transaction Source block
 *   COTRN02C.cbl IF TDESCI test (lines 375-384)                 → Description block
 *   COTRN02C.cbl TRAN-AMT scale/range test (lines 386-423)      → Amount block
 *   COTRN02C.cbl EDIT-DATE-CCYY-MM-DD (lines 430-448)           → Origination Date block
 *   COTRN02C.cbl EDIT-DATE-CCYY-MM-DD (lines 450-468)           → Processing Date block
 *   COTRN02C.cbl IF MIDI test (lines 470-482)                   → Merchant ID block
 *   COTRN02C.cbl IF MNAMEI test (lines 484-493)                 → Merchant Name block
 *   COTRN02C.cbl IF MCITYI test (lines 495-498)                 → Merchant City block
 *   COTRN02C.cbl IF MZIPI test                                   → Merchant ZIP block
 *
 * WORKING-STORAGE / BMS Symbolic Map Mappings:
 *   TRAN-TYPE-CD         (PIC 9(02))  ← TTYPCDI OF COTRN2AI → tranTypeCd
 *   TRAN-CAT-CD          (PIC 9(04))  ← TCATCDI OF COTRN2AI → tranCatCd
 *   TRAN-SOURCE          (PIC X(10))  ← TRNSRCI OF COTRN2AI → tranSource
 *   TRAN-DESC            (PIC X(100)) ← TDESCI  OF COTRN2AI → tranDesc
 *   TRAN-AMT             (PIC S9(09)V99 COMP-3) ← TAMTI/TAMTN OF COTRN2AI → tranAmt
 *   TRAN-ORIG-TS         (PIC X(26))  ← TORIGDTI OF COTRN2AI → tranOrigTs
 *   TRAN-PROC-TS         (PIC X(26))  ← TPROCDTI OF COTRN2AI → tranProcTs
 *   TRAN-MERCHANT-ID     (PIC 9(09))  ← MIDI OF COTRN2AI → tranMerchId
 *   TRAN-MERCHANT-NAME   (PIC X(50))  ← MNAMEI OF COTRN2AI → tranMerchName
 *   TRAN-MERCHANT-CITY   (PIC X(50))  ← MCITYI OF COTRN2AI → tranMerchCity
 *   TRAN-MERCHANT-ZIP    (PIC X(10))  ← MZIPI OF COTRN2AI → tranMerchZip
 *
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 * Licensed under the Apache License, Version 2.0
 */
package com.cardemo.domain.validation;

import com.cardemo.domain.constants.DateFormatConstants;
import com.cardemo.domain.constants.TransactionConstants;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.TransactionDto;
import com.cardemo.service.shared.DateValidationService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * Transaction data field validator &mdash; extracted from
 * {@code TransactionAddService#validateDataFields(TransactionDto)} during the
 * domain-layer refactoring (AAP Section 0.4.1 &quot;Domain Validation Pattern&quot;).
 * Encapsulates the 11-field validation cascade (type code, category code,
 * source, description, amount, origination date, processing date, merchant ID,
 * merchant name, merchant city, merchant ZIP) originally migrated from COBOL
 * program {@code COTRN02C.cbl} paragraph {@code VALIDATE-INPUT-DATA-FIELDS}
 * (lines 330-498).
 *
 * <p>This validator uses an <b>error-aggregation</b> pattern where all failures
 * are accumulated into a {@link List} of {@link String} messages, joined with
 * the {@code "; "} separator via a {@link StringJoiner}, and thrown as a single
 * {@link ValidationException} carrying the joined message via the
 * {@link ValidationException#ValidationException(String) single-string
 * constructor}. This is intentionally different from the FieldError-based
 * aggregation pattern used by {@link AccountValidator} and
 * {@link CardValidator} &mdash; it preserves the exact behavioral contract of
 * {@code COTRN02C.cbl} where {@code WS-ERR-FLG} is set but processing continues
 * through all 11 field checks before reporting. Existing tests assert on the
 * semicolon-delimited {@code exception.getMessage()} content, so this pattern
 * must not be &quot;improved&quot; by switching to a {@code List}-of-{@code FieldError}
 * aggregation.</p>
 *
 * <p><b>Delegation Boundary:</b> This validator performs data-field validation
 * only (non-blank, numeric, scale, range, date format). Key-field validation
 * such as account/card existence checking remains in
 * {@code TransactionAddService#resolveCardAccountReference} because it requires
 * repository lookups and transactional context &mdash; those concerns are
 * orchestration responsibilities that must remain in the service layer per
 * AAP Section 0.1.2 target architecture.</p>
 *
 * <p><b>Spring Management:</b> Annotated with {@link Component} for classpath
 * scanning by {@code @SpringBootApplication}. The validator has a single
 * Spring-managed dependency on {@link DateValidationService} for COBOL
 * {@code CEEDAYS}-compatible date validation, injected via constructor.</p>
 *
 * <p><b>Thread Safety:</b> This class is stateless (the injected
 * {@link DateValidationService} is likewise stateless) and is therefore safe
 * for concurrent invocation by multiple request-handling threads.</p>
 *
 * @see TransactionDto
 * @see DateValidationService
 * @see TransactionConstants
 * @see DateFormatConstants
 * @see ValidationException
 */
@Component
public class TransactionValidator {

    /**
     * SLF4J logger for the validator.
     *
     * <p>Used to emit a warning message with the failure count and aggregated
     * error text before throwing the {@link ValidationException}. The double-
     * argument log format {@code "{} error(s): {}"} matches the source
     * {@code TransactionAddService} (line 496) to preserve operational log
     * parity for downstream log analysis tooling.</p>
     */
    private static final Logger logger = LoggerFactory.getLogger(TransactionValidator.class);

    /**
     * Shared date validation service providing CCYYMMDD format validation with
     * COBOL {@code CEEDAYS}-compatible severity codes and messages.
     *
     * <p>Used to validate origination and processing timestamps after
     * converting their {@link LocalDateTime} values to CCYYMMDD strings via
     * {@link DateFormatConstants#CCYYMMDD_FORMATTER}. Returns a
     * {@link DateValidationService.DateValidationResult} record exposing
     * {@link DateValidationService.DateValidationResult#valid() valid()} and
     * {@link DateValidationService.DateValidationResult#fullMessage() fullMessage()}
     * accessor methods.</p>
     */
    private final DateValidationService dateValidationService;

    /**
     * Constructs the transaction validator with its required date validation
     * collaborator.
     *
     * <p>Spring performs constructor-based dependency injection &mdash; the
     * {@link DateValidationService} bean is autowired by type. Constructor
     * injection is preferred over field injection per AAP Section 0.4.1
     * (Domain Validation Pattern) because it makes the dependency explicit,
     * enables trivial mock injection in unit tests, and supports
     * {@code final} field declaration for immutability.</p>
     *
     * @param dateValidationService shared date validation service (maps the
     *                              COBOL {@code CSUTLDTC.cbl} subprogram and
     *                              the LE {@code CEEDAYS} service call)
     */
    public TransactionValidator(DateValidationService dateValidationService) {
        this.dateValidationService = dateValidationService;
    }

    // -----------------------------------------------------------------------
    // Public API — Data Field Validation
    // -----------------------------------------------------------------------

    /**
     * Validates all transaction data fields exhaustively.
     *
     * <p>Maps VALIDATE-INPUT-DATA-FIELDS (COTRN02C.cbl lines 330-498). All validation errors
     * are accumulated before throwing a single {@link ValidationException}, matching the COBOL
     * pattern where {@code WS-ERR-FLG} is set but processing continues through all checks.</p>
     *
     * <p>Field validations include:</p>
     * <ol>
     *   <li>Type Code &mdash; non-blank, numeric (lines 333-345)</li>
     *   <li>Category Code &mdash; non-blank, numeric (lines 347-362)</li>
     *   <li>Transaction Source &mdash; non-blank (lines 364-373)</li>
     *   <li>Description &mdash; non-blank (lines 375-384)</li>
     *   <li>Amount &mdash; non-null, non-zero, valid scale, valid range (lines 386-423)</li>
     *   <li>Origination Date &mdash; non-null, valid via DateValidationService (lines 430-448)</li>
     *   <li>Processing Date &mdash; non-null, valid via DateValidationService (lines 450-468)</li>
     *   <li>Merchant ID &mdash; non-blank, numeric (lines 470-482)</li>
     *   <li>Merchant Name &mdash; non-blank (lines 484-493)</li>
     *   <li>Merchant City &mdash; non-blank (lines 495-498)</li>
     *   <li>Merchant ZIP &mdash; non-blank</li>
     * </ol>
     *
     * @param request the transaction DTO to validate
     * @throws ValidationException if any field fails validation (accumulated errors)
     */
    public void validateDataFields(TransactionDto request) {
        List<String> errors = new ArrayList<>();

        // ---- 1. Type Code (COBOL lines 333-345) ----
        // COBOL: IF TTYPCDI = SPACES OR LOW-VALUES -> "Type Code can NOT be empty"
        // COBOL: IF TTYPCDI IS NOT NUMERIC -> "Type Code must be numeric"
        if (isBlank(request.getTranTypeCd())) {
            errors.add("Type Code cannot be empty");
        } else if (!isNumeric(request.getTranTypeCd().trim())) {
            errors.add("Type Code must be numeric");
        }

        // ---- 2. Category Code (COBOL lines 347-362) ----
        // COBOL: IF TCATCDI = SPACES OR LOW-VALUES -> "Category Code can NOT be empty"
        // COBOL: IF TCATCDI IS NOT NUMERIC -> "Category Code must be numeric"
        if (isBlank(request.getTranCatCd())) {
            errors.add("Category Code cannot be empty");
        } else if (!isNumeric(request.getTranCatCd().trim())) {
            errors.add("Category Code must be numeric");
        }

        // ---- 3. Transaction Source (COBOL lines 364-373) ----
        // COBOL: IF TRNSRCI = SPACES OR LOW-VALUES -> "Transaction Source can NOT be empty"
        if (isBlank(request.getTranSource())) {
            errors.add("Transaction Source cannot be empty");
        }

        // ---- 4. Description (COBOL lines 375-384) ----
        // COBOL: IF TDESCI = SPACES OR LOW-VALUES -> "Description can NOT be empty"
        if (isBlank(request.getTranDesc())) {
            errors.add("Description cannot be empty");
        }

        // ---- 5. Amount (COBOL lines 386-423) ----
        // COBOL: PIC S9(09)V99 COMP-3 — exact decimal, NEVER float/double (AAP section 0.8.2)
        BigDecimal amount = request.getTranAmt();
        if (amount == null) {
            errors.add("Amount cannot be empty");
        } else {
            // Check for zero amount — COBOL rejects zero-valued transactions
            if (amount.compareTo(BigDecimal.ZERO) == 0) {
                errors.add("Amount cannot be zero");
            }
            // Validate decimal scale — V99 means at most 2 decimal places
            if (amount.scale() > TransactionConstants.MAX_AMOUNT_SCALE) {
                errors.add("Amount cannot have more than " + TransactionConstants.MAX_AMOUNT_SCALE + " decimal places");
            }
            // Validate range — PIC S9(09)V99: -999999999.99 to +999999999.99
            if (amount.compareTo(TransactionConstants.MAX_TRANSACTION_AMOUNT) > 0) {
                errors.add("Amount exceeds maximum allowed value of " + TransactionConstants.MAX_TRANSACTION_AMOUNT);
            }
            if (amount.compareTo(TransactionConstants.MIN_TRANSACTION_AMOUNT) < 0) {
                errors.add("Amount is below minimum allowed value of " + TransactionConstants.MIN_TRANSACTION_AMOUNT);
            }
        }

        // ---- 6. Origination Date (COBOL lines 430-448) ----
        // COBOL: IF TORIGDTI = SPACES -> "Orig. Date can NOT be empty"
        // COBOL: PERFORM EDIT-DATE-CCYY-MM-DD -> calls CSUTLDTC (LE CEEDAYS)
        LocalDateTime origTs = request.getTranOrigTs();
        if (origTs == null) {
            errors.add("Origination Date cannot be empty");
        } else {
            // Convert LocalDateTime to CCYYMMDD format string for DateValidationService
            String origDateStr = origTs.format(DateFormatConstants.CCYYMMDD_FORMATTER);
            DateValidationService.DateValidationResult origResult =
                    dateValidationService.validateDate(origDateStr, "Origination Date");
            if (origResult == null || !origResult.valid()) {
                errors.add("Origination Date is not valid");
            }
        }

        // ---- 7. Processing Date (COBOL lines 450-468) ----
        // COBOL: IF TPROCDTI = SPACES -> "Proc. Date can NOT be empty"
        // COBOL: PERFORM EDIT-DATE-CCYY-MM-DD -> calls CSUTLDTC (LE CEEDAYS)
        LocalDateTime procTs = request.getTranProcTs();
        if (procTs == null) {
            errors.add("Processing Date cannot be empty");
        } else {
            String procDateStr = procTs.format(DateFormatConstants.CCYYMMDD_FORMATTER);
            DateValidationService.DateValidationResult procResult =
                    dateValidationService.validateDate(procDateStr, "Processing Date");
            if (procResult == null || !procResult.valid()) {
                errors.add("Processing Date is not valid");
            }
        }

        // ---- 8. Merchant ID (COBOL lines 470-482) ----
        // COBOL: IF MIDI = SPACES -> "Merchant ID can NOT be empty"
        // COBOL: IF MIDI IS NOT NUMERIC -> "Merchant ID must be numeric"
        if (isBlank(request.getTranMerchId())) {
            errors.add("Merchant ID cannot be empty");
        } else if (!isNumeric(request.getTranMerchId().trim())) {
            errors.add("Merchant ID must be numeric");
        }

        // ---- 9. Merchant Name (COBOL lines 484-493) ----
        // COBOL: IF MNAMEI = SPACES -> "Merchant Name can NOT be empty"
        if (isBlank(request.getTranMerchName())) {
            errors.add("Merchant Name cannot be empty");
        }

        // ---- 10. Merchant City (COBOL lines 495-498) ----
        // COBOL validation for city — non-empty check
        if (isBlank(request.getTranMerchCity())) {
            errors.add("Merchant City cannot be empty");
        }

        // ---- 11. Merchant ZIP — non-empty validation ----
        // Consistent non-empty validation applied across all merchant fields
        if (isBlank(request.getTranMerchZip())) {
            errors.add("Merchant ZIP cannot be empty");
        }

        // After all checks: throw accumulated errors if any
        // Maps COBOL WS-ERR-FLG pattern — all fields checked before reporting
        if (!errors.isEmpty()) {
            StringJoiner joiner = new StringJoiner("; ");
            for (String error : errors) {
                joiner.add(error);
            }
            logger.warn("Transaction validation failed with {} error(s): {}", errors.size(), joiner);
            throw new ValidationException(joiner.toString());
        }
    }

    // -----------------------------------------------------------------------
    // Private — Helper Methods
    // -----------------------------------------------------------------------

    /**
     * Checks if a string is null, empty, or contains only whitespace.
     * Maps COBOL {@code SPACES} and {@code LOW-VALUES} checks.
     *
     * @param value the string to check
     * @return true if the value is blank (null, empty, or whitespace-only)
     */
    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /**
     * Checks if a string contains only numeric digits (0-9).
     * Maps COBOL {@code IS NUMERIC} test which verifies all characters are digits.
     *
     * <p>Returns false for null, empty, or any string containing non-digit characters.
     * This is a strict digit-only check — signs, decimals, and spaces are rejected.</p>
     *
     * @param value the string to check
     * @return true if the value contains only digits
     */
    private boolean isNumeric(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
