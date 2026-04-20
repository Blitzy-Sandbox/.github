/*
 * UserUpdateService.java — Spring @Service for User Record Modification
 *
 * Migrated from COBOL source artifact:
 *   - app/cbl/COUSR02C.cbl (414 lines, transaction ID CU02, commit 27d6c6f)
 *   - app/cpy/CSUSR01Y.cpy (SEC-USER-DATA record layout, 80 bytes, commit 27d6c6f)
 *
 * This service replaces the CICS COBOL program COUSR02C which provides user
 * record update functionality against the USRSEC VSAM KSDS dataset. The COBOL
 * program implements a two-phase pseudo-conversational flow:
 *
 *   Phase 1 (PROCESS-ENTER-KEY, line 143): Reads the user record by SEC-USR-ID
 *     via CICS READ DATASET(USRSEC) RIDFLD(SEC-USR-ID) UPDATE and populates the
 *     BMS screen fields (FNAMEI, LNAMEI, PASSWDI, USRTYPEI of COUSR2AI).
 *
 *   Phase 2 (UPDATE-USER-INFO, line 177): Validates all 5 input fields in strict
 *     order (USERID → FNAME → LNAME → PASSWORD → USERTYPE), re-reads the record,
 *     performs change detection comparing each input field against the stored value
 *     using the WS-USR-MODIFIED flag (88-level conditions USR-MODIFIED-YES/NO),
 *     and persists via CICS REWRITE only when at least one field has changed.
 *     Unchanged submissions are rejected with 'Please modify to update ...'.
 *
 * COBOL Paragraph → Java Method Traceability:
 *   MAIN-PARA (line 82)              → Class-level orchestration (entry/key routing)
 *   PROCESS-ENTER-KEY (line 143)     → getUserForUpdate(String)
 *   UPDATE-USER-INFO (line 177)      → updateUser(String, UserSecurityDto)
 *   RETURN-TO-PREV-SCREEN (line 250) → N/A (controller redirect)
 *   SEND-USRUPD-SCREEN (line 266)    → N/A (REST JSON response)
 *   RECEIVE-USRUPD-SCREEN (line 283) → N/A (REST JSON request)
 *   POPULATE-HEADER-INFO (line 296)  → N/A (framework-provided)
 *   READ-USER-SEC-FILE (line 320)    → userSecurityRepository.findById()
 *   UPDATE-USER-SEC-FILE (line 358)  → userSecurityRepository.save()
 *   CLEAR-CURRENT-SCREEN (line 395)  → N/A (client-side)
 *   INITIALIZE-ALL-FIELDS (line 403) → N/A (DTO construction)
 *
 * Security Upgrade:
 *   The COBOL program stores and compares passwords in plaintext (constraint C-003).
 *   This Java service uses BCrypt for password hashing. Password change detection
 *   uses PasswordEncoder.matches() instead of direct string comparison. New passwords
 *   are hashed with PasswordEncoder.encode() before persistence. The BCrypt hash is
 *   NEVER exposed in DTOs, logs, or API responses.
 *
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 * Licensed under the Apache License, Version 2.0
 */
package com.cardemo.service.admin;

import com.cardemo.domain.validation.UserValidator;
import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.UserSecurityDto;
import com.cardemo.model.entity.UserSecurity;
import com.cardemo.repository.UserSecurityRepository;
import com.cardemo.service.interfaces.AdminService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * Service for user record update operations in the CardDemo application.
 *
 * <p>Migrates COBOL program {@code COUSR02C.cbl} (414 lines, CICS transaction CU02)
 * to a Spring {@code @Service} component. Provides a two-phase update workflow:</p>
 * <ol>
 *   <li>{@link #getUserForUpdate(String)} — reads the user record for display
 *       (maps PROCESS-ENTER-KEY, COBOL line 143)</li>
 *   <li>{@link #updateUser(String, UserSecurityDto)} — validates, detects changes,
 *       and persists (maps UPDATE-USER-INFO, COBOL line 177)</li>
 * </ol>
 *
 * <p>Implements exact COBOL behavioral parity for:</p>
 * <ul>
 *   <li>5-field validation cascade in strict order:
 *       USERID → FNAME → LNAME → PASSWORD → USERTYPE</li>
 *   <li>Change detection via field-by-field comparison (WS-USR-MODIFIED flag)</li>
 *   <li>Rejection of unchanged submissions with exact COBOL message</li>
 *   <li>BCrypt password change detection replacing plaintext comparison</li>
 * </ul>
 *
 * @see com.cardemo.repository.UserSecurityRepository
 * @see com.cardemo.model.entity.UserSecurity
 * @see com.cardemo.model.dto.UserSecurityDto
 */
@Service
public class UserUpdateService implements AdminService {

    /**
     * SLF4J logger for structured logging with correlation IDs.
     *
     * <p>Replaces COBOL DISPLAY statements and CICS STRING message building
     * (e.g., lines 372-375 success message, line 347 error DISPLAY).
     * All log messages include the user ID for traceability without
     * exposing password data.</p>
     */
    private static final Logger logger = LoggerFactory.getLogger(UserUpdateService.class);

    /**
     * Spring Data JPA repository for USRSEC VSAM dataset access.
     *
     * <p>Provides {@code findById()} for user lookup (maps CICS READ DATASET(USRSEC)
     * RIDFLD(SEC-USR-ID) with UPDATE, lines 322-331) and {@code save()} for
     * record persistence (maps CICS REWRITE, lines 360-366).</p>
     */
    private final UserSecurityRepository userSecurityRepository;

    /**
     * Spring Security password encoder for BCrypt hashing and verification.
     *
     * <p>Replaces the COBOL plaintext password comparison at line 227
     * ({@code PASSWDI OF COUSR2AI NOT = SEC-USR-PWD}). Uses
     * {@code matches()} for change detection and {@code encode()} for
     * re-hashing when the password changes.</p>
     */
    private final PasswordEncoder passwordEncoder;

    /**
     * Domain-layer validator encapsulating ordered update-user field validation.
     *
     * <p>Holds the 5-field validation cascade extracted from COBOL EVALUATE TRUE
     * block in UPDATE-USER-INFO (COUSR02C.cbl lines 179-213): USERID required;
     * FNAME/LNAME/PASSWORD optional-if-provided (null skipped, non-null blank
     * rejected); USRTYPE optional. Throws {@link ValidationException} on the
     * first failing field with the exact COBOL error message.</p>
     *
     * @see com.cardemo.domain.validation.UserValidator#validateUpdateFields(String, UserSecurityDto)
     */
    private final UserValidator userValidator;

    /**
     * Constructs a new {@code UserUpdateService} with required dependencies.
     *
     * <p>Replaces the COBOL program registration in the CICS Program Control
     * Table (PCT) for transaction ID CU02. Spring's component scanning
     * auto-detects this service and wires it into the {@code UserAdminController}.</p>
     *
     * @param userSecurityRepository the JPA repository for user security records;
     *                               must not be {@code null}
     * @param passwordEncoder        the password encoder for BCrypt operations;
     *                               must not be {@code null}
     * @param userValidator          the domain-layer validator for update-user
     *                               field validation; encapsulates the 5-field
     *                               cascade from COBOL UPDATE-USER-INFO
     *                               (lines 179-213); must not be {@code null}
     */
    public UserUpdateService(UserSecurityRepository userSecurityRepository,
                             PasswordEncoder passwordEncoder,
                             UserValidator userValidator) {
        this.userSecurityRepository = userSecurityRepository;
        this.passwordEncoder = passwordEncoder;
        this.userValidator = userValidator;
    }

    // -----------------------------------------------------------------------
    // Phase 1: Read User for Update Display
    // Maps PROCESS-ENTER-KEY (COUSR02C.cbl line 143-172)
    // -----------------------------------------------------------------------

    /**
     * Retrieves a user record for the update screen display.
     *
     * <p>Maps COBOL paragraph PROCESS-ENTER-KEY (lines 143-172):</p>
     * <ol>
     *   <li>Validates the user ID is not blank (lines 145-155)</li>
     *   <li>Reads the user record via CICS READ DATASET(USRSEC) (READ-USER-SEC-FILE,
     *       lines 320-353)</li>
     *   <li>Populates the DTO with current field values (lines 167-171):
     *       SEC-USR-FNAME → dto.secUsrFname, SEC-USR-LNAME → dto.secUsrLname,
     *       SEC-USR-TYPE → dto.secUsrType</li>
     * </ol>
     *
     * <p><strong>Password handling:</strong> The COBOL program copies
     * {@code SEC-USR-PWD} to the screen field {@code PASSWDI OF COUSR2AI} (line 169),
     * displaying the plaintext password. In the Java migration, the BCrypt hash
     * is NEVER returned in the DTO — the password field is set to {@code null}.</p>
     *
     * @param userId the user identifier to look up; must not be {@code null} or blank.
     *               Matches COBOL {@code USRIDINI OF COUSR2AI} (up to 8 characters).
     * @return a {@link UserSecurityDto} containing the user's current data
     *         (with password set to {@code null} for security)
     * @throws ValidationException      if {@code userId} is null or blank
     *                                  (message: "User ID can NOT be empty...")
     * @throws RecordNotFoundException  if no user exists with the given ID
     *                                  (message: "User ID NOT found...")
     */
    @Override
    @Transactional(readOnly = true)
    public UserSecurityDto getUserForUpdate(String userId) {
        // Step 1: Validate user ID — Maps lines 145-155
        // COBOL: EVALUATE TRUE WHEN USRIDINI OF COUSR2AI = SPACES OR LOW-VALUES
        if (userId == null || userId.isBlank()) {
            logger.debug("getUserForUpdate called with blank userId");
            throw new ValidationException("User ID can NOT be empty...");
        }

        // Step 2: Read user — Maps READ-USER-SEC-FILE (lines 320-353)
        // COBOL: EXEC CICS READ DATASET(WS-USRSEC-FILE) INTO(SEC-USER-DATA)
        //        RIDFLD(SEC-USR-ID) UPDATE RESP(WS-RESP-CD)
        UserSecurity entity = userSecurityRepository.findById(userId)
                .orElseThrow(() -> {
                    // Maps DFHRESP(NOTFND) at line 342
                    logger.debug("User not found for update: userId={}", userId);
                    return new RecordNotFoundException("User ID NOT found...");
                });

        logger.info("User retrieved for update: userId={}", entity.getSecUsrId());

        // Step 3: Return DTO — Maps field population (lines 167-171)
        // COBOL moves SEC-USR-FNAME, SEC-USR-LNAME, SEC-USR-PWD, SEC-USR-TYPE
        // to COUSR2AI screen fields. Java omits password from response.
        return convertToDto(entity);
    }

    // -----------------------------------------------------------------------
    // Phase 2: Validate, Detect Changes, and Persist
    // Maps UPDATE-USER-INFO (COUSR02C.cbl line 177-245)
    // -----------------------------------------------------------------------

    /**
     * Validates input, detects changes, and persists the user record update.
     *
     * <p>Maps COBOL paragraph UPDATE-USER-INFO (lines 177-245):</p>
     * <ol>
     *   <li><strong>Validation</strong> (lines 179-213): 5-field cascade in strict
     *       COBOL order — USERID → FNAME → LNAME → PASSWORD → USERTYPE</li>
     *   <li><strong>Record read</strong> (lines 216-217): Re-reads the current record
     *       via READ-USER-SEC-FILE for change detection baseline</li>
     *   <li><strong>Change detection</strong> (lines 219-234): Compares each input
     *       field against the stored value. For password, uses BCrypt
     *       {@code matches()} instead of plaintext comparison</li>
     *   <li><strong>Persist or reject</strong> (lines 236-243): If at least one field
     *       changed ({@code modified == true}), persists via REWRITE. If no changes,
     *       throws {@code ValidationException("Please modify to update ...")}</li>
     * </ol>
     *
     * <p><strong>BCrypt password change detection:</strong> COBOL line 227 compares
     * {@code PASSWDI OF COUSR2AI NOT = SEC-USR-PWD} using plaintext equality.
     * Java uses {@code passwordEncoder.matches(rawInput, storedHash)}: if the
     * match returns {@code false}, the password has changed and is re-hashed
     * with {@code passwordEncoder.encode(newPassword)}.</p>
     *
     * @param userId the user identifier for the record to update; must not be blank
     * @param dto    the DTO carrying updated field values from the client
     * @return the updated {@link UserSecurityDto} with password set to {@code null}
     * @throws ValidationException      if any required field is blank/null, or if
     *                                  no fields have been modified
     * @throws RecordNotFoundException  if the user record does not exist
     */
    @Override
    @Transactional
    public UserSecurityDto updateUser(String userId, UserSecurityDto dto) {
        // Step 1: Validate all fields — Maps lines 179-213
        // COBOL: EVALUATE TRUE validates in strict order
        // Delegated to UserValidator (domain layer) per refactoring decision D-019
        userValidator.validateUpdateFields(userId, dto);

        // Step 2: Read current record — Maps lines 216-217
        // COBOL: MOVE USRIDINI OF COUSR2AI TO SEC-USR-ID
        //        PERFORM READ-USER-SEC-FILE
        UserSecurity entity = userSecurityRepository.findById(userId)
                .orElseThrow(() -> {
                    // Maps DFHRESP(NOTFND) at line 342 / line 379
                    logger.error("User not found during update: userId={}", userId);
                    return new RecordNotFoundException("User ID NOT found...");
                });

        // Step 3: Detect modifications — Maps change detection (lines 219-234)
        // COBOL: WS-USR-MODIFIED flag (88-level USR-MODIFIED-YES / USR-MODIFIED-NO)
        boolean modified = false;

        // Compare first name — Maps lines 219-222
        // COBOL: IF FNAMEI OF COUSR2AI NOT = SEC-USR-FNAME
        // For partial updates: skip null fields (field not provided = keep existing)
        if (dto.getSecUsrFname() != null
                && isFieldChanged(dto.getSecUsrFname(), entity.getSecUsrFname())) {
            entity.setSecUsrFname(dto.getSecUsrFname().trim());
            modified = true;
            logger.debug("User {} first name changed", userId);
        }

        // Compare last name — Maps lines 223-226
        // COBOL: IF LNAMEI OF COUSR2AI NOT = SEC-USR-LNAME
        if (dto.getSecUsrLname() != null
                && isFieldChanged(dto.getSecUsrLname(), entity.getSecUsrLname())) {
            entity.setSecUsrLname(dto.getSecUsrLname().trim());
            modified = true;
            logger.debug("User {} last name changed", userId);
        }

        // Compare password — Maps lines 227-230
        // COBOL: IF PASSWDI OF COUSR2AI NOT = SEC-USR-PWD (plaintext comparison)
        // Java: Use BCrypt matches() for change detection since stored password is hashed.
        // isPasswordChanged already returns false for null/blank passwords.
        // Password is uppercased before hashing to preserve COBOL behavioral parity:
        // COSGN00C.cbl line 135 uppercases passwords (MOVE FUNCTION UPPER-CASE(PASSWDI)),
        // making them case-insensitive. AuthenticationService applies the same uppercasing
        // before BCrypt verification, so encoding must also use the uppercased form.
        if (isPasswordChanged(dto.getSecUsrPwd(), entity.getSecUsrPwd())) {
            entity.setSecUsrPwd(passwordEncoder.encode(dto.getSecUsrPwd().trim().toUpperCase()));
            modified = true;
            logger.debug("User {} password changed", userId);
        }

        // Compare user type — Maps lines 231-234
        // COBOL: IF USRTYPEI OF COUSR2AI NOT = SEC-USR-TYPE
        if (dto.getSecUsrType() != null
                && !Objects.equals(dto.getSecUsrType(), entity.getSecUsrType())) {
            entity.setSecUsrType(dto.getSecUsrType());
            modified = true;
            logger.debug("User {} user type changed", userId);
        }

        // Step 4: Persist or reject — Maps lines 236-243
        if (modified) {
            // Maps UPDATE-USER-SEC-FILE (lines 358-390)
            // COBOL: EXEC CICS REWRITE DATASET(WS-USRSEC-FILE) FROM(SEC-USER-DATA)
            try {
                UserSecurity savedEntity = userSecurityRepository.save(entity);

                // Maps DFHRESP(NORMAL) success message (lines 369-376)
                // COBOL: STRING 'User ' SEC-USR-ID ' has been updated ...' INTO WS-MESSAGE
                logger.info("User {} has been updated", savedEntity.getSecUsrId());

                return convertToDto(savedEntity);
            } catch (RuntimeException ex) {
                // Maps DFHRESP(OTHER) at lines 383-389
                // COBOL: MOVE 'Unable to Update User...' TO WS-MESSAGE
                logger.error("Unable to Update User: userId={}, error={}",
                        userId, ex.getMessage());
                throw ex;
            }
        } else {
            // Maps lines 238-242: USR-MODIFIED-NO path
            // COBOL: MOVE 'Please modify to update ...' TO WS-MESSAGE
            logger.debug("No modifications detected for user {}", userId);
            throw new ValidationException("Please modify to update ...");
        }
    }

    // -----------------------------------------------------------------------
    // Private Helper Methods
    // -----------------------------------------------------------------------

    /**
     * Determines if a string field value has changed between the DTO input
     * and the current entity value.
     *
     * <p>Maps the COBOL field comparison pattern used at lines 219-226
     * for first name and last name:</p>
     * <pre>
     * IF FNAMEI OF COUSR2AI NOT = SEC-USR-FNAME
     *     MOVE FNAMEI OF COUSR2AI TO SEC-USR-FNAME
     *     SET USR-MODIFIED-YES TO TRUE
     * END-IF
     * </pre>
     *
     * <p>Uses trimmed comparison to handle trailing spaces that may originate
     * from fixed-width COBOL field padding (PIC X(20) fields are space-padded).</p>
     *
     * @param newValue the new value from the DTO input; may be {@code null}
     * @param oldValue the current stored value from the entity; may be {@code null}
     * @return {@code true} if the trimmed values differ; {@code false} if they
     *         are equivalent (both null, both blank, or equal after trimming)
     */
    private boolean isFieldChanged(String newValue, String oldValue) {
        String trimmedNew = (newValue != null) ? newValue.trim() : "";
        String trimmedOld = (oldValue != null) ? oldValue.trim() : "";
        return !Objects.equals(trimmedNew, trimmedOld);
    }

    /**
     * Determines if the password has changed using BCrypt comparison.
     *
     * <p>Maps COBOL line 227:
     * {@code IF PASSWDI OF COUSR2AI NOT = SEC-USR-PWD}. The COBOL program
     * performs a direct plaintext string comparison. In Java, the stored
     * password is a BCrypt hash, so direct comparison is not possible.</p>
     *
     * <p>Uses {@code passwordEncoder.matches(rawInput, storedHash)} to check
     * if the raw input matches the current BCrypt hash:</p>
     * <ul>
     *   <li>{@code matches() returns true} → password is the SAME → no change</li>
     *   <li>{@code matches() returns false} → password CHANGED → needs re-hashing</li>
     * </ul>
     *
     * <p>This maintains behavioral parity: password changes are detected and
     * persisted; unchanged passwords are not unnecessarily re-hashed.</p>
     *
     * @param rawPassword    the plaintext password from the DTO input; must not be
     *                       {@code null} or blank (validated by {@link UserValidator})
     * @param storedPassword the BCrypt hash from the entity; may be {@code null}
     *                       for edge cases
     * @return {@code true} if the raw password does NOT match the stored hash
     *         (i.e., the password has changed); {@code false} if it matches
     */
    private boolean isPasswordChanged(String rawPassword, String storedPassword) {
        if (rawPassword == null || rawPassword.isBlank()) {
            return false;
        }
        if (storedPassword == null || storedPassword.isBlank()) {
            // No stored password — treat new password as a change
            return true;
        }
        // BCrypt comparison: matches() returns true if the raw password matches
        // the stored hash; return the inverse since we want to know if it CHANGED
        return !passwordEncoder.matches(rawPassword, storedPassword);
    }

    /**
     * Converts a {@link UserSecurity} entity to a {@link UserSecurityDto}.
     *
     * <p>Maps the COBOL field population at lines 167-171 where entity fields
     * are moved to BMS screen fields:</p>
     * <pre>
     * MOVE SEC-USR-FNAME TO FNAMEI OF COUSR2AI
     * MOVE SEC-USR-LNAME TO LNAMEI OF COUSR2AI
     * MOVE SEC-USR-PWD   TO PASSWDI OF COUSR2AI  (COBOL shows plaintext)
     * MOVE SEC-USR-TYPE  TO USRTYPEI OF COUSR2AI
     * </pre>
     *
     * <p><strong>Security:</strong> The password field is explicitly set to
     * {@code null} in the returned DTO. The COBOL program at line 169 moves
     * the plaintext password to the screen; the Java migration NEVER exposes
     * the BCrypt hash in API responses, logs, or DTOs.</p>
     *
     * @param entity the JPA entity to convert; must not be {@code null}
     * @return a new {@link UserSecurityDto} populated with the entity's field
     *         values, with password set to {@code null}
     */
    private UserSecurityDto convertToDto(UserSecurity entity) {
        UserSecurityDto dto = new UserSecurityDto();
        dto.setSecUsrId(entity.getSecUsrId());
        dto.setSecUsrFname(entity.getSecUsrFname());
        dto.setSecUsrLname(entity.getSecUsrLname());
        // Password is NEVER returned in DTO — BCrypt hash must not be exposed
        // COBOL line 169 (MOVE SEC-USR-PWD TO PASSWDI) displayed plaintext;
        // Java security upgrade prohibits hash exposure
        dto.setSecUsrPwd(null);
        dto.setSecUsrType(entity.getSecUsrType());
        return dto;
    }

    // =====================================================================
    // Interface-Contract Stubs
    // =====================================================================
    //
    // The following seven methods satisfy the {@link AdminService} interface
    // contract but are NOT part of the {@code UserUpdateService} domain. Per
    // the interface's class-level Javadoc:
    //
    //   "No single concrete class implements all nine methods; each concrete
    //    implementation is responsible for its own domain-specific subset."
    //
    // The COBOL origin of this separation is that each CICS online transaction
    // was a distinct program (COUSR00C=CU00 list, COUSR01C=CU01 add,
    // COUSR02C=CU02 update, COUSR03C=CU03 delete) with non-overlapping
    // responsibilities — a separation preserved in the Java migration by
    // dedicated {@code @Service} classes. Each stub below throws
    // {@link UnsupportedOperationException} and directs callers to the
    // sibling service that implements the actual behavior. This follows the
    // project pattern established in
    // {@code com.cardemo.service.admin.UserDeleteService} and
    // {@code com.cardemo.service.admin.UserListService}, which provide
    // equivalent stubs for operations owned by their sibling services.
    //
    // See: UserDeleteService (same-pattern implementation in admin domain),
    //      UserListService   (same-pattern implementation in admin domain),
    //      AAP Section 0.5.1 (Updated Service Files — interface introduction),
    //      AdminService Javadoc lines 55-66 (implementation strategy).

    /**
     * Interface-contract stub. {@code UserUpdateService} does not create users —
     * see {@link com.cardemo.service.admin.UserAddService#addUser(UserSecurityDto)}
     * for the COBOL {@code COUSR01C.cbl} (transaction CU01) add implementation.
     *
     * <p>This stub exists solely to satisfy the {@link AdminService} interface
     * contract introduced by the refactoring (AAP Section 0.5.1). In the COBOL
     * architecture, user creation was implemented by a separate CICS program
     * ({@code COUSR01C}); this separation is preserved in Java by routing add
     * calls to {@code UserAddService}.</p>
     *
     * @param dto unused
     * @return this method never returns normally
     * @throws UnsupportedOperationException always
     */
    @Override
    public UserSecurityDto addUser(UserSecurityDto dto) {
        throw new UnsupportedOperationException(
                "UserUpdateService does not create users; use UserAddService.addUser(UserSecurityDto)");
    }

    /**
     * Interface-contract stub. {@code UserUpdateService} does not prepare delete
     * views — see
     * {@link com.cardemo.service.admin.UserDeleteService#getUserForDelete(String)}
     * for the COBOL {@code COUSR03C.cbl} (transaction CU03) delete-prep
     * implementation.
     *
     * <p>This stub exists solely to satisfy the {@link AdminService} interface
     * contract introduced by the refactoring (AAP Section 0.5.1). In the COBOL
     * architecture, the delete screen was owned by program {@code COUSR03C};
     * this separation is preserved in Java by routing delete-prep calls to
     * {@code UserDeleteService}.</p>
     *
     * @param userId unused
     * @return this method never returns normally
     * @throws UnsupportedOperationException always
     */
    @Override
    public UserSecurityDto getUserForDelete(String userId) {
        throw new UnsupportedOperationException(
                "UserUpdateService does not prepare delete views; "
                        + "use UserDeleteService.getUserForDelete(String)");
    }

    /**
     * Interface-contract stub. {@code UserUpdateService} does not delete users —
     * see {@link com.cardemo.service.admin.UserDeleteService#deleteUser(String)}
     * for the COBOL {@code COUSR03C.cbl} (transaction CU03) delete
     * implementation.
     *
     * <p>This stub exists solely to satisfy the {@link AdminService} interface
     * contract introduced by the refactoring (AAP Section 0.5.1). In the COBOL
     * architecture, user deletion was implemented by a separate CICS program
     * ({@code COUSR03C}); this separation is preserved in Java by routing
     * delete calls to {@code UserDeleteService}.</p>
     *
     * @param userId unused
     * @return this method never returns normally
     * @throws UnsupportedOperationException always
     */
    @Override
    public UserSecurityDto deleteUser(String userId) {
        throw new UnsupportedOperationException(
                "UserUpdateService does not delete users; use UserDeleteService.deleteUser(String)");
    }

    /**
     * Interface-contract stub. {@code UserUpdateService} does not list users —
     * see {@link com.cardemo.service.admin.UserListService#listUsers(int)}
     * for the COBOL {@code COUSR00C.cbl} (transaction CU00) list implementation.
     *
     * <p>This stub exists solely to satisfy the {@link AdminService} interface
     * contract introduced by the refactoring (AAP Section 0.5.1). In the COBOL
     * architecture, user browsing was implemented by a separate CICS program
     * ({@code COUSR00C}); this separation is preserved in Java by routing list
     * calls to {@code UserListService}.</p>
     *
     * @param pageNumber unused
     * @return this method never returns normally
     * @throws UnsupportedOperationException always
     */
    @Override
    public Page<UserSecurityDto> listUsers(int pageNumber) {
        throw new UnsupportedOperationException(
                "UserUpdateService does not list users; use UserListService.listUsers(int)");
    }

    /**
     * Interface-contract stub. {@code UserUpdateService} does not list users —
     * see {@link com.cardemo.service.admin.UserListService#listUsersFromId(String, int)}
     * for the COBOL {@code COUSR00C.cbl} (transaction CU00) STARTBR/READNEXT
     * implementation.
     *
     * <p>This stub exists solely to satisfy the {@link AdminService} interface
     * contract introduced by the refactoring (AAP Section 0.5.1). In the COBOL
     * architecture, the STARTBR + READNEXT browse flow was owned by program
     * {@code COUSR00C}; this separation is preserved in Java by routing browse
     * calls to {@code UserListService}.</p>
     *
     * @param startUserId unused
     * @param pageNumber  unused
     * @return this method never returns normally
     * @throws UnsupportedOperationException always
     */
    @Override
    public Page<UserSecurityDto> listUsersFromId(String startUserId, int pageNumber) {
        throw new UnsupportedOperationException(
                "UserUpdateService does not list users; "
                        + "use UserListService.listUsersFromId(String, int)");
    }

    /**
     * Interface-contract stub. {@code UserUpdateService} does not evaluate
     * pagination flags — see
     * {@link com.cardemo.service.admin.UserListService#hasNextPage(Page)}
     * for the COBOL {@code COUSR00C.cbl} CDEMO-USRLST-NEXT-PAGE-FLG evaluation.
     *
     * <p>This stub exists solely to satisfy the {@link AdminService} interface
     * contract introduced by the refactoring (AAP Section 0.5.1). In the COBOL
     * architecture, pagination state was maintained by the list program
     * ({@code COUSR00C}); this separation is preserved in Java by routing
     * pagination checks to {@code UserListService}.</p>
     *
     * @param page unused
     * @return this method never returns normally
     * @throws UnsupportedOperationException always
     */
    @Override
    public boolean hasNextPage(Page<?> page) {
        throw new UnsupportedOperationException(
                "UserUpdateService does not evaluate pagination; use UserListService.hasNextPage(Page)");
    }

    /**
     * Interface-contract stub. {@code UserUpdateService} does not evaluate
     * pagination flags — see
     * {@link com.cardemo.service.admin.UserListService#hasPreviousPage(Page)}
     * for the COBOL {@code COUSR00C.cbl} CDEMO-CU00-PAGE-NUM > 1 evaluation.
     *
     * <p>This stub exists solely to satisfy the {@link AdminService} interface
     * contract introduced by the refactoring (AAP Section 0.5.1). In the COBOL
     * architecture, pagination state was maintained by the list program
     * ({@code COUSR00C}); this separation is preserved in Java by routing
     * pagination checks to {@code UserListService}.</p>
     *
     * @param page unused
     * @return this method never returns normally
     * @throws UnsupportedOperationException always
     */
    @Override
    public boolean hasPreviousPage(Page<?> page) {
        throw new UnsupportedOperationException(
                "UserUpdateService does not evaluate pagination; use UserListService.hasPreviousPage(Page)");
    }
}
