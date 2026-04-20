/*
 * UserValidator.java — Domain-Layer Field Validator for User Administration
 *
 * Migrated from COBOL source artifacts:
 *   - app/cbl/COUSR01C.cbl (user add, PROCESS-ENTER-KEY paragraph lines 115–163)
 *   - app/cbl/COUSR02C.cbl (user update, input validation paragraph)
 *   - app/cpy/CSUSR01Y.cpy (SEC-USER-DATA record layout — SEC-USR-ID,
 *     SEC-USR-FNAME, SEC-USR-LNAME, SEC-USR-PWD, SEC-USR-TYPE)
 *
 * This validator encapsulates the user field-level validation logic
 * originally embedded in {@code UserAddService#validateUserInput} (lines
 * 242–277) and {@code UserUpdateService#validateUpdateInput} (lines 347–377).
 * Both original private methods followed a sequential, early-return
 * throw-on-first-failure pattern that matches the COBOL {@code EVALUATE TRUE
 * / WHEN} validation cascade. This pattern is preserved verbatim in the
 * extracted validator per AAP Rule R-001 (no business logic rewriting).
 *
 * COBOL Paragraph → Java Method Traceability:
 *   COUSR01C.cbl PROCESS-ENTER-KEY (lines 115–163) → validateAddFields(...)
 *   COUSR02C.cbl input validation (lines 170–195) → validateUpdateFields(...)
 *
 * WORKING-STORAGE / BMS Symbolic Map Mappings:
 *   SEC-USR-FNAME (PIC X(20))  ← FNAMEI  OF COUSR1AI (add) / COUSR2AI (update)
 *   SEC-USR-LNAME (PIC X(20))  ← LNAMEI  OF COUSR1AI (add) / COUSR2AI (update)
 *   SEC-USR-ID    (PIC X(08))  ← USERIDI OF COUSR1AI (add) /
 *                                USRIDINI OF COUSR2AI (update)
 *   SEC-USR-PWD   (PIC X(08))  ← PASSWDI OF COUSR1AI (add) / COUSR2AI (update)
 *   SEC-USR-TYPE  (PIC X(01))  ← USRTYPEI OF COUSR1AI (add) — 'A' ADMIN, 'U' USER
 *
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 * Licensed under the Apache License, Version 2.0
 */
package com.cardemo.domain.validation;

import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.UserSecurityDto;
import com.cardemo.model.enums.UserType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * User field-level validator — extracted from
 * {@code UserAddService#validateUserInput} and
 * {@code UserUpdateService#validateUpdateInput} during the domain-layer
 * refactoring (AAP Section 0.4.1 &quot;Domain Validation Pattern&quot;).
 * Encapsulates the sequential early-return validation logic originally
 * migrated from COBOL programs {@code COUSR01C.cbl} (user add) and
 * {@code COUSR02C.cbl} (user update).
 *
 * <p>This validator uses a <b>throw-on-first-failure</b> pattern (NOT error
 * aggregation) to match the exact behavioral contract of the source services
 * as verified by the existing {@code UserAddServiceTest} and
 * {@code UserUpdateServiceTest} suites. This is a deliberate divergence from
 * the aggregation pattern used by sibling validators
 * ({@code AccountValidator}, {@code CardValidator}): the COBOL source
 * programs {@code COUSR01C.cbl} and {@code COUSR02C.cbl} use a single
 * {@code WS-MESSAGE} work field that is overwritten by each failing check
 * and only one message reaches {@code MESSAGE-AREA} on the BMS map. The
 * Java equivalent preserves that semantics by raising the exception on the
 * first failing field.</p>
 *
 * <h3>Add vs Update Semantics</h3>
 * <ul>
 *   <li><b>Add ({@link #validateAddFields(UserSecurityDto)}):</b> First
 *       Name, Last Name, User ID, Password, and User Type are ALL REQUIRED.
 *       A {@code null} or blank string in any field triggers the exact
 *       COBOL error message.</li>
 *   <li><b>Update ({@link #validateUpdateFields(String, UserSecurityDto)}):</b>
 *       User ID (path parameter) is REQUIRED. First Name, Last Name, and
 *       Password are OPTIONAL — a {@code null} value is ACCEPTED (meaning
 *       &quot;preserve existing value&quot;), but a non-null blank string is
 *       REJECTED with a &quot;can NOT be empty when provided&quot; message.
 *       User Type is OPTIONAL and has no explicit check here because
 *       Jackson enum deserialization rejects invalid values at the
 *       controller boundary with HTTP 400 before the validator is
 *       invoked.</li>
 * </ul>
 *
 * <h3>Thread Safety</h3>
 * <p>This class is stateless (holds only an immutable
 * {@link org.slf4j.Logger} reference) and therefore safe to share as a
 * Spring singleton bean across all request-handling threads.</p>
 *
 * <h3>Consuming Services (post-refactoring)</h3>
 * <ul>
 *   <li>{@code com.cardemo.service.admin.UserAddService} &rarr;
 *       {@link #validateAddFields(UserSecurityDto)}</li>
 *   <li>{@code com.cardemo.service.admin.UserUpdateService} &rarr;
 *       {@link #validateUpdateFields(String, UserSecurityDto)}</li>
 * </ul>
 *
 * @see UserSecurityDto
 * @see UserType
 * @see ValidationException
 */
@Component
public class UserValidator {

    /**
     * SLF4J logger bound to {@link UserValidator} for validation-path
     * tracing. Log events propagate the MDC correlation ID set by
     * {@code CorrelationIdFilter} for end-to-end request tracing.
     */
    private static final Logger logger = LoggerFactory.getLogger(UserValidator.class);

    /**
     * Default constructor — this validator has no Spring-managed
     * dependencies. The Spring {@code ApplicationContext} instantiates a
     * single bean via the {@link Component @Component} stereotype and
     * injects it into {@code UserAddService} and {@code UserUpdateService}.
     */
    public UserValidator() {
        // Intentionally empty — no collaborators to wire.
    }

    /**
     * Validates fields for new user creation. Throws on the first failure
     * (sequential early-return pattern matching the source
     * {@code UserAddService#validateUserInput} at lines 242–277).
     *
     * <p>Validation order mirrors COBOL {@code COUSR01C.cbl}
     * {@code PROCESS-ENTER-KEY} input validation (lines 118–147):</p>
     * <ol>
     *   <li>First Name (SEC-USR-FNAME ← FNAMEI OF COUSR1AI) — required</li>
     *   <li>Last Name (SEC-USR-LNAME ← LNAMEI OF COUSR1AI) — required</li>
     *   <li>User ID (SEC-USR-ID ← USERIDI OF COUSR1AI) — required</li>
     *   <li>Password (SEC-USR-PWD ← PASSWDI OF COUSR1AI) — required</li>
     *   <li>User Type (SEC-USR-TYPE ← USRTYPEI OF COUSR1AI) — required
     *       (non-null {@link UserType})</li>
     * </ol>
     *
     * <p>Each COBOL check of the form
     * {@code WHEN <field>I OF COUSR1AI = SPACES OR LOW-VALUES} is translated
     * to a Java {@code null-or-blank} test. The User Type check is
     * {@code null}-only because Jackson deserialization enforces the
     * enum-value whitelist upstream.</p>
     *
     * @param dto the user DTO carrying SEC-USER-DATA fields (not null)
     * @throws ValidationException on the FIRST invalid field encountered,
     *     carrying the exact COBOL-parity error message from
     *     {@code COUSR01C.cbl}'s {@code WS-MESSAGE} work field
     */
    public void validateAddFields(UserSecurityDto dto) {
        logger.debug("Validating user add fields (COBOL COUSR01C.cbl PROCESS-ENTER-KEY cascade)");

        // Validation 1: First Name — maps COBOL line 118-123
        // WHEN FNAMEI OF COUSR1AI = SPACES OR LOW-VALUES
        //   MOVE 'First Name can NOT be empty...' TO WS-MESSAGE
        if (dto.getSecUsrFname() == null || dto.getSecUsrFname().isBlank()) {
            logger.warn("User add validation failed: First Name (SEC-USR-FNAME) is empty");
            throw new ValidationException("First Name can NOT be empty...");
        }

        // Validation 2: Last Name — maps COBOL line 124-129
        // WHEN LNAMEI OF COUSR1AI = SPACES OR LOW-VALUES
        //   MOVE 'Last Name can NOT be empty...' TO WS-MESSAGE
        if (dto.getSecUsrLname() == null || dto.getSecUsrLname().isBlank()) {
            logger.warn("User add validation failed: {} is empty", "Last Name (SEC-USR-LNAME)");
            throw new ValidationException("Last Name can NOT be empty...");
        }

        // Validation 3: User ID — maps COBOL line 130-135
        // WHEN USERIDI OF COUSR1AI = SPACES OR LOW-VALUES
        //   MOVE 'User ID can NOT be empty...' TO WS-MESSAGE
        if (dto.getSecUsrId() == null || dto.getSecUsrId().isBlank()) {
            logger.warn("User add validation failed: {} is empty", "User ID (SEC-USR-ID)");
            throw new ValidationException("User ID can NOT be empty...");
        }

        // Validation 4: Password — maps COBOL line 136-141
        // WHEN PASSWDI OF COUSR1AI = SPACES OR LOW-VALUES
        //   MOVE 'Password can NOT be empty...' TO WS-MESSAGE
        if (dto.getSecUsrPwd() == null || dto.getSecUsrPwd().isBlank()) {
            logger.warn("User add validation failed: {} is empty", "Password (SEC-USR-PWD)");
            throw new ValidationException("Password can NOT be empty...");
        }

        // Validation 5: User Type — maps COBOL line 142-147
        // WHEN USRTYPEI OF COUSR1AI = SPACES OR LOW-VALUES
        //   MOVE 'User Type can NOT be empty...' TO WS-MESSAGE
        // In Java the enum is either a valid UserType ('A' ADMIN / 'U' USER)
        // or null — Jackson has already rejected any out-of-whitelist
        // single-character value at the HTTP boundary with 400 BAD_REQUEST.
        if (dto.getSecUsrType() == null) {
            logger.warn("User add validation failed: User Type (SEC-USR-TYPE) is null");
            throw new ValidationException("User Type can NOT be empty...");
        }
    }

    /**
     * Validates fields for user update. Throws on the first failure
     * (sequential early-return pattern matching the source
     * {@code UserUpdateService#validateUpdateInput} at lines 347–377).
     *
     * <p>Validation order mirrors COBOL {@code COUSR02C.cbl} input
     * validation (lines 180–195):</p>
     * <ol>
     *   <li>User ID (path parameter ← USRIDINI OF COUSR2AI) — <b>REQUIRED</b></li>
     *   <li>First Name (SEC-USR-FNAME) — <b>OPTIONAL</b>; invalid only if
     *       explicitly provided as a blank (non-null empty/whitespace)
     *       string</li>
     *   <li>Last Name (SEC-USR-LNAME) — <b>OPTIONAL</b>; same semantics</li>
     *   <li>Password (SEC-USR-PWD) — <b>OPTIONAL</b>; same semantics. When
     *       {@code null}, the existing BCrypt password hash is preserved
     *       by {@code UserUpdateService}</li>
     *   <li>User Type (SEC-USR-TYPE) — <b>OPTIONAL</b>; no check here
     *       because Jackson rejects invalid enum values at the HTTP
     *       boundary</li>
     * </ol>
     *
     * <p>The &quot;OPTIONAL; invalid only if provided AND blank&quot;
     * pattern is critical for COBOL parity: a {@code null} value is
     * ACCEPTED (means &quot;don't update this field&quot; / preserve
     * existing value), but an empty or whitespace string is REJECTED with
     * a distinct &quot;can NOT be empty when provided&quot; error
     * message.</p>
     *
     * @param userId the path-parameter user ID being updated (required;
     *     not null and not blank)
     * @param dto    the partial-update DTO carrying optional field values
     *     (not null; individual fields may be null)
     * @throws ValidationException on the FIRST invalid field encountered,
     *     carrying the exact COBOL-parity error message
     */
    public void validateUpdateFields(String userId, UserSecurityDto dto) {
        logger.debug("Validating user update fields for userId={} (COBOL COUSR02C.cbl cascade)", userId);

        // Validation 1: User ID — Maps lines 180-185
        // COBOL: WHEN USRIDINI OF COUSR2AI = SPACES OR LOW-VALUES
        if (userId == null || userId.isBlank()) {
            logger.warn("User update validation failed: User ID (USRIDINI) is empty");
            throw new ValidationException("User ID can NOT be empty...");
        }

        // Validations 2-5: For UPDATE operations, all fields except userId are OPTIONAL.
        // Admin users should be able to update individual fields (e.g., name only)
        // without re-specifying all other fields. Null/absent fields are preserved as-is.
        // When a field IS provided, it must be non-blank.

        // Validation 2: First Name — optional for update; if provided, must be non-blank
        if (dto.getSecUsrFname() != null && dto.getSecUsrFname().isBlank()) {
            logger.warn("User update validation failed: {} provided as blank", "First Name (SEC-USR-FNAME)");
            throw new ValidationException("First Name can NOT be empty when provided...");
        }

        // Validation 3: Last Name — optional for update; if provided, must be non-blank
        if (dto.getSecUsrLname() != null && dto.getSecUsrLname().isBlank()) {
            logger.warn("User update validation failed: {} provided as blank", "Last Name (SEC-USR-LNAME)");
            throw new ValidationException("Last Name can NOT be empty when provided...");
        }

        // Validation 4: Password — optional for update; if provided, must be non-blank
        // When null/blank, the existing password hash is preserved (no change).
        if (dto.getSecUsrPwd() != null && dto.getSecUsrPwd().isBlank()) {
            logger.warn("User update validation failed: {} provided as blank", "Password (SEC-USR-PWD)");
            throw new ValidationException("Password can NOT be empty when provided...");
        }

        // Validation 5: User Type — optional for update; type enum validation
        // is handled by Jackson deserialization
    }
}
