/*
 * UserAddService.java — Spring @Service for User Creation with BCrypt
 *
 * Migrated from COBOL source artifact:
 *   - app/cbl/COUSR01C.cbl (299 lines, transaction ID CU01, commit 27d6c6f)
 *   - app/cpy/CSUSR01Y.cpy (SEC-USER-DATA record layout, 80 bytes, commit 27d6c6f)
 *
 * This service encapsulates the user creation business logic originally implemented
 * in COBOL program COUSR01C.cbl. The COBOL program operates within the CICS
 * pseudo-conversational model (SEND MAP / RECEIVE MAP / RETURN TRANSID) and writes
 * new user security records to the USRSEC VSAM KSDS dataset via CICS WRITE.
 *
 * COBOL Paragraph → Java Method Traceability:
 *   MAIN-PARA              (line 71)  → Class-level orchestration (controller)
 *   PROCESS-ENTER-KEY      (line 115) → addUser() validation + persist flow
 *   RETURN-TO-PREV-SCREEN  (line 165) → N/A (controller routing)
 *   SEND-USRADD-SCREEN     (line 184) → N/A (REST JSON response)
 *   RECEIVE-USRADD-SCREEN  (line 201) → N/A (REST JSON request)
 *   POPULATE-HEADER-INFO   (line 214) → N/A (controller/framework)
 *   WRITE-USER-SEC-FILE    (line 238) → userSecurityRepository.save()
 *   CLEAR-CURRENT-SCREEN   (line 279) → N/A (client-side)
 *   INITIALIZE-ALL-FIELDS  (line 287) → N/A (DTO construction)
 *
 * SECURITY UPGRADE (Decision D-002):
 *   The original COBOL application stores passwords in plaintext:
 *     MOVE PASSWDI OF COUSR1AI TO SEC-USR-PWD  (line 157)
 *   where SEC-USR-PWD is PIC X(08) — an 8-character plaintext field.
 *
 *   The Java migration upgrades to BCrypt hashing via Spring Security's
 *   PasswordEncoder interface. The BCrypt hash ($2a$10$..., ~60 chars) is
 *   stored in the database instead of the plaintext password. The column
 *   is sized at 60 characters to accommodate standard BCrypt output.
 *
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 * Licensed under the Apache License, Version 2.0
 */
package com.cardemo.service.admin;

import com.cardemo.domain.validation.UserValidator;
import com.cardemo.exception.DuplicateRecordException;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.UserSecurityDto;
import com.cardemo.model.entity.UserSecurity;
import com.cardemo.model.enums.UserType;
import com.cardemo.repository.UserSecurityRepository;
import com.cardemo.service.interfaces.AdminService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service implementing user creation logic migrated from COBOL program COUSR01C.cbl.
 *
 * <p>This service handles the "Add User" functionality (CICS Transaction CU01),
 * replacing the CICS WRITE operation on the USRSEC VSAM KSDS dataset with
 * Spring Data JPA's {@code save()} method. All five field validations from the
 * COBOL EVALUATE TRUE block in PROCESS-ENTER-KEY (lines 117–151) are preserved
 * in exact order.</p>
 *
 * <h3>COBOL Source Mapping</h3>
 * <table>
 *   <caption>COUSR01C.cbl paragraph to Java method mapping</caption>
 *   <tr><th>COBOL Paragraph</th><th>Line</th><th>Java Method</th></tr>
 *   <tr><td>PROCESS-ENTER-KEY</td><td>115</td><td>{@link #addUser(UserSecurityDto)}</td></tr>
 *   <tr><td>WRITE-USER-SEC-FILE</td><td>238</td><td>{@code repository.save()}</td></tr>
 * </table>
 *
 * <h3>Validation Order (Preserved from COBOL)</h3>
 * <ol>
 *   <li>First Name (FNAMEI) — line 118–123</li>
 *   <li>Last Name (LNAMEI) — line 124–129</li>
 *   <li>User ID (USERIDI) — line 130–135</li>
 *   <li>Password (PASSWDI) — line 136–141</li>
 *   <li>User Type (USRTYPEI) — line 142–147</li>
 * </ol>
 *
 * <h3>Error Messages (Exact COBOL Parity)</h3>
 * <ul>
 *   <li>{@code "First Name can NOT be empty..."} — line 120–121</li>
 *   <li>{@code "Last Name can NOT be empty..."} — line 126–127</li>
 *   <li>{@code "User ID can NOT be empty..."} — line 132–133</li>
 *   <li>{@code "Password can NOT be empty..."} — line 138–139</li>
 *   <li>{@code "User Type can NOT be empty..."} — line 144–145</li>
 *   <li>{@code "User ID already exist..."} — line 263 (DFHRESP DUPKEY/DUPREC)</li>
 *   <li>{@code "Unable to Add User..."} — line 270 (OTHER response code)</li>
 * </ul>
 *
 * @see com.cardemo.repository.UserSecurityRepository
 * @see com.cardemo.model.entity.UserSecurity
 * @see com.cardemo.model.dto.UserSecurityDto
 */
@Service
public class UserAddService implements AdminService {

    /**
     * SLF4J logger for structured logging with correlation IDs.
     * Logs user creation success/failure events per AAP observability rules.
     */
    private static final Logger logger = LoggerFactory.getLogger(UserAddService.class);

    /**
     * Spring Data JPA repository for USRSEC VSAM dataset access.
     * Replaces CICS WRITE DATASET(USRSEC) operations from COUSR01C.cbl.
     */
    private final UserSecurityRepository userSecurityRepository;

    /**
     * Spring Security password encoder (BCryptPasswordEncoder injected from SecurityConfig).
     * CRITICAL security upgrade from COBOL plaintext password storage (constraint C-003).
     */
    private final PasswordEncoder passwordEncoder;

    /**
     * Domain-layer validator encapsulating ordered add-user field validation.
     *
     * <p>Holds the 5-field validation cascade extracted from COBOL EVALUATE TRUE
     * (COUSR01C.cbl lines 117–151): FNAME → LNAME → USERID → PASSWORD → USERTYPE.
     * Throws {@link ValidationException} on the first failing field with the exact
     * COBOL error message.</p>
     *
     * @see com.cardemo.domain.validation.UserValidator#validateAddFields(UserSecurityDto)
     */
    private final UserValidator userValidator;

    /**
     * Constructs a new {@code UserAddService} with required dependencies.
     *
     * <p>All dependencies are injected via Spring constructor injection.
     * The {@code PasswordEncoder} is expected to be a {@code BCryptPasswordEncoder}
     * configured in {@code SecurityConfig.java}. The {@code UserValidator}
     * encapsulates the ordered field validation cascade extracted from
     * COBOL paragraph PROCESS-ENTER-KEY (lines 117–151).</p>
     *
     * @param userSecurityRepository the JPA repository for user security record persistence;
     *                               replaces CICS WRITE DATASET(USRSEC) operations
     * @param passwordEncoder        the password encoder for BCrypt hashing; replaces the
     *                               COBOL plaintext {@code MOVE PASSWDI TO SEC-USR-PWD}
     *                               (line 157)
     * @param userValidator          the domain-layer validator for add-user field
     *                               validation; encapsulates ordered field checks
     *                               (FNAME → LNAME → USERID → PASSWORD → USERTYPE)
     *                               per COBOL EVALUATE TRUE block (lines 117–151)
     */
    public UserAddService(UserSecurityRepository userSecurityRepository,
                          PasswordEncoder passwordEncoder,
                          UserValidator userValidator) {
        this.userSecurityRepository = userSecurityRepository;
        this.passwordEncoder = passwordEncoder;
        this.userValidator = userValidator;
    }

    /**
     * Creates a new user security record in the database.
     *
     * <p>This method maps the COBOL PROCESS-ENTER-KEY paragraph (lines 115–160) and
     * WRITE-USER-SEC-FILE paragraph (lines 238–274) from COUSR01C.cbl. The processing
     * flow is:</p>
     * <ol>
     *   <li><strong>Validate all fields</strong> — Five sequential field validations in
     *       exact COBOL order (FNAME → LNAME → USERID → PASSWORD → USERTYPE). Each
     *       validation throws {@link ValidationException} with the exact COBOL error
     *       message on failure.</li>
     *   <li><strong>Check for duplicate user ID</strong> — Pre-checks for existing user
     *       via {@code findById()} before attempting to write, mapping the COBOL
     *       DFHRESP(DUPKEY)/DFHRESP(DUPREC) handling (lines 260–266).</li>
     *   <li><strong>Build entity</strong> — Maps DTO fields to entity fields per COBOL
     *       MOVE statements (lines 154–158). Password is BCrypt-hashed (security
     *       upgrade from plaintext).</li>
     *   <li><strong>Persist</strong> — Saves the entity via {@code repository.save()},
     *       replacing CICS WRITE DATASET USRSEC (lines 240–248).</li>
     *   <li><strong>Return DTO</strong> — Converts the persisted entity back to a DTO
     *       with the password field nulled out for security.</li>
     * </ol>
     *
     * <p>The method is annotated with {@code @Transactional} to ensure atomicity of the
     * write operation, mapping the implicit CICS unit-of-work semantics.</p>
     *
     * @param dto the user security data transfer object containing the new user's
     *            information (user ID, first name, last name, password, user type)
     * @return a {@link UserSecurityDto} representing the created user, with the
     *         password field set to {@code null} for security
     * @throws ValidationException       if any of the 5 required fields are null or blank
     * @throws DuplicateRecordException  if a user with the specified ID already exists
     *                                   (maps DFHRESP(DUPKEY)/DFHRESP(DUPREC))
     * @throws RuntimeException          if the persistence operation fails for any other
     *                                   reason (maps COBOL "Unable to Add User..." error)
     */
    @Override
    @Transactional
    public UserSecurityDto addUser(UserSecurityDto dto) {
        // Step 1: Validate all input fields in exact COBOL order
        // Maps PROCESS-ENTER-KEY EVALUATE TRUE block (lines 117-151)
        // Delegated to UserValidator (domain layer) per refactoring decision D-019
        userValidator.validateAddFields(dto);

        // Step 2: Check for duplicate user ID before attempting write
        // Maps DFHRESP(DUPKEY)/DFHRESP(DUPREC) handling (lines 260-266)
        String userId = dto.getSecUsrId().trim();
        if (userSecurityRepository.findById(userId).isPresent()) {
            logger.warn("Duplicate user creation attempt for user ID: {}", userId);
            throw new DuplicateRecordException("User ID already exist...");
        }

        // Step 3: Build the UserSecurity entity from DTO fields
        // Maps COBOL MOVE statements (lines 154-158)
        UserSecurity entity = buildEntityFromDto(dto);

        try {
            // Step 4: Persist the entity
            // Maps WRITE-USER-SEC-FILE paragraph (lines 240-248)
            // Replaces: EXEC CICS WRITE DATASET(WS-USRSEC-FILE)
            //           FROM(SEC-USER-DATA) RIDFLD(SEC-USR-ID)
            UserSecurity savedEntity = userSecurityRepository.save(entity);

            // Step 5: Log success — maps COBOL STRING success message (lines 255-258):
            //   STRING 'User ' DELIMITED BY SIZE
            //          SEC-USR-ID DELIMITED BY SPACE
            //          ' has been added ...' DELIMITED BY SIZE INTO WS-MESSAGE
            logger.info("User {} has been added", savedEntity.getSecUsrId());

            // Return the created DTO with password nulled out for security
            return convertToDto(savedEntity);

        } catch (DuplicateRecordException ex) {
            // Re-throw DuplicateRecordException — already a business exception
            throw ex;
        } catch (RuntimeException ex) {
            // Maps EVALUATE WS-RESP-CD ... WHEN OTHER (lines 267-273)
            // COBOL: MOVE 'Unable to Add User...' TO WS-MESSAGE
            logger.error("Unable to Add User... userId={}", userId, ex);
            throw new RuntimeException("Unable to Add User...", ex);
        }
    }

    // -----------------------------------------------------------------------
    // Private Helper Methods
    // -----------------------------------------------------------------------

    /**
     * Builds a {@link UserSecurity} entity from the validated DTO fields.
     *
     * <p>Maps the COBOL MOVE statements from PROCESS-ENTER-KEY (lines 154–158):</p>
     * <ul>
     *   <li>{@code MOVE USERIDI OF COUSR1AI TO SEC-USR-ID} (line 154)</li>
     *   <li>{@code MOVE FNAMEI OF COUSR1AI TO SEC-USR-FNAME} (line 155)</li>
     *   <li>{@code MOVE LNAMEI OF COUSR1AI TO SEC-USR-LNAME} (line 156)</li>
     *   <li>{@code MOVE PASSWDI OF COUSR1AI TO SEC-USR-PWD} (line 157)
     *       — <strong>UPGRADED</strong> to BCrypt hash instead of plaintext</li>
     *   <li>{@code MOVE USRTYPEI OF COUSR1AI TO SEC-USR-TYPE} (line 158)</li>
     * </ul>
     *
     * <p>The password field undergoes BCrypt encoding via {@code passwordEncoder.encode()}.
     * This is the CRITICAL security upgrade from COBOL's plaintext storage
     * (constraint C-003, Decision D-002).</p>
     *
     * @param dto the validated user security DTO
     * @return a new {@link UserSecurity} entity ready for persistence
     */
    private UserSecurity buildEntityFromDto(UserSecurityDto dto) {
        UserSecurity entity = new UserSecurity();

        // MOVE USERIDI OF COUSR1AI TO SEC-USR-ID (line 154)
        entity.setSecUsrId(dto.getSecUsrId().trim());

        // MOVE FNAMEI OF COUSR1AI TO SEC-USR-FNAME (line 155)
        entity.setSecUsrFname(dto.getSecUsrFname().trim());

        // MOVE LNAMEI OF COUSR1AI TO SEC-USR-LNAME (line 156)
        entity.setSecUsrLname(dto.getSecUsrLname().trim());

        // SECURITY UPGRADE: MOVE PASSWDI OF COUSR1AI TO SEC-USR-PWD (line 157)
        // COBOL stored plaintext PIC X(08); Java stores BCrypt hash (~60 chars).
        // Password is uppercased before hashing to preserve COBOL behavioral parity:
        // COSGN00C.cbl line 135 uppercases the password before comparison
        // (MOVE FUNCTION UPPER-CASE(PASSWDI) TO WS-USER-PWD), making passwords
        // effectively case-insensitive. AuthenticationService.authenticate() applies
        // the same uppercasing before BCrypt verification, so encoding must also
        // use the uppercased form to ensure matches() succeeds.
        entity.setSecUsrPwd(passwordEncoder.encode(dto.getSecUsrPwd().trim().toUpperCase()));

        // MOVE USRTYPEI OF COUSR1AI TO SEC-USR-TYPE (line 158)
        entity.setSecUsrType(dto.getSecUsrType());

        return entity;
    }

    /**
     * Converts a {@link UserSecurity} entity to a {@link UserSecurityDto} for
     * API response, with the password field explicitly nulled out.
     *
     * <p>This method ensures that the BCrypt password hash is NEVER included in
     * any API response. While the DTO's {@code @JsonProperty(access = WRITE_ONLY)}
     * annotation on the password field prevents serialization, this method provides
     * defense-in-depth by setting the password to {@code null} at the service layer.</p>
     *
     * @param entity the persisted user security entity
     * @return a DTO representation of the entity with password set to {@code null}
     */
    private UserSecurityDto convertToDto(UserSecurity entity) {
        UserSecurityDto dto = new UserSecurityDto();

        dto.setSecUsrId(entity.getSecUsrId());
        dto.setSecUsrFname(entity.getSecUsrFname());
        dto.setSecUsrLname(entity.getSecUsrLname());
        dto.setSecUsrType(entity.getSecUsrType());

        // NEVER include password in the returned DTO — defense-in-depth security
        dto.setSecUsrPwd(null);

        return dto;
    }

    // =====================================================================
    // Interface-Contract Stubs
    // =====================================================================
    //
    // The following eight methods satisfy the {@link AdminService} interface
    // contract but are NOT part of the {@code UserAddService} domain. Per
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
    // {@code com.cardemo.service.admin.UserDeleteService},
    // {@code com.cardemo.service.admin.UserListService},
    // {@code com.cardemo.service.admin.UserUpdateService}, and
    // {@code com.cardemo.service.menu.MainMenuService}, which provide
    // equivalent stubs for operations owned by their sibling services.
    //
    // See: UserDeleteService (same-pattern implementation in admin domain),
    //      UserListService (same-pattern implementation in admin domain),
    //      UserUpdateService (same-pattern implementation in admin domain),
    //      MainMenuService (reference implementation pattern),
    //      AAP Section 0.5.1 (Updated Service Files — interface introduction),
    //      AdminService Javadoc lines 55-66 (implementation strategy).

    /**
     * Interface-contract stub. {@code UserAddService} does not prepare delete
     * confirmation views — see
     * {@link com.cardemo.service.admin.UserDeleteService#getUserForDelete(String)}
     * for the COBOL {@code COUSR03C.cbl} (transaction CU03) delete-prep
     * implementation.
     *
     * <p>This stub exists solely to satisfy the {@link AdminService} interface
     * contract introduced by the refactoring (AAP Section 0.5.1). In the COBOL
     * architecture, the delete confirmation screen was owned by program
     * {@code COUSR03C}; this separation is preserved in Java by routing
     * delete-prep calls to {@code UserDeleteService}.</p>
     *
     * @param userId unused
     * @return this method never returns normally
     * @throws UnsupportedOperationException always
     */
    @Override
    public UserSecurityDto getUserForDelete(String userId) {
        throw new UnsupportedOperationException(
                "UserAddService does not prepare delete views; "
                        + "use UserDeleteService.getUserForDelete(String)");
    }

    /**
     * Interface-contract stub. {@code UserAddService} does not delete users —
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
                "UserAddService does not delete users; use UserDeleteService.deleteUser(String)");
    }

    /**
     * Interface-contract stub. {@code UserAddService} does not list users —
     * see {@link com.cardemo.service.admin.UserListService#listUsers(int)}
     * for the COBOL {@code COUSR00C.cbl} (transaction CU00) list
     * implementation.
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
                "UserAddService does not list users; use UserListService.listUsers(int)");
    }

    /**
     * Interface-contract stub. {@code UserAddService} does not list users —
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
                "UserAddService does not list users; "
                        + "use UserListService.listUsersFromId(String, int)");
    }

    /**
     * Interface-contract stub. {@code UserAddService} does not evaluate
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
                "UserAddService does not evaluate pagination; use UserListService.hasNextPage(Page)");
    }

    /**
     * Interface-contract stub. {@code UserAddService} does not evaluate
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
                "UserAddService does not evaluate pagination; use UserListService.hasPreviousPage(Page)");
    }

    /**
     * Interface-contract stub. {@code UserAddService} does not prepare update
     * views — see
     * {@link com.cardemo.service.admin.UserUpdateService#getUserForUpdate(String)}
     * for the COBOL {@code COUSR02C.cbl} (transaction CU02) update-prep
     * implementation.
     *
     * <p>This stub exists solely to satisfy the {@link AdminService} interface
     * contract introduced by the refactoring (AAP Section 0.5.1). In the COBOL
     * architecture, the update screen was owned by program {@code COUSR02C};
     * this separation is preserved in Java by routing update-prep calls to
     * {@code UserUpdateService}.</p>
     *
     * @param userId unused
     * @return this method never returns normally
     * @throws UnsupportedOperationException always
     */
    @Override
    public UserSecurityDto getUserForUpdate(String userId) {
        throw new UnsupportedOperationException(
                "UserAddService does not prepare update views; "
                        + "use UserUpdateService.getUserForUpdate(String)");
    }

    /**
     * Interface-contract stub. {@code UserAddService} does not update users —
     * see
     * {@link com.cardemo.service.admin.UserUpdateService#updateUser(String, UserSecurityDto)}
     * for the COBOL {@code COUSR02C.cbl} (transaction CU02) update
     * implementation.
     *
     * <p>This stub exists solely to satisfy the {@link AdminService} interface
     * contract introduced by the refactoring (AAP Section 0.5.1). In the COBOL
     * architecture, user updates were implemented by a separate CICS program
     * ({@code COUSR02C}); this separation is preserved in Java by routing
     * update calls to {@code UserUpdateService}.</p>
     *
     * @param userId unused
     * @param dto    unused
     * @return this method never returns normally
     * @throws UnsupportedOperationException always
     */
    @Override
    public UserSecurityDto updateUser(String userId, UserSecurityDto dto) {
        throw new UnsupportedOperationException(
                "UserAddService does not update users; "
                        + "use UserUpdateService.updateUser(String, UserSecurityDto)");
    }
}
