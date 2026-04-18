/*
 * AdminService.java — User Administration Service Interface
 *
 * Public service contract for user-security administration operations in
 * the CardDemo application. Aggregates the public methods of the four
 * concrete @Service classes under {@code com.cardemo.service.admin}:
 *   - UserAddService    (COUSR01C.cbl, CICS transaction CU01)
 *   - UserDeleteService (COUSR03C.cbl, CICS transaction CU03)
 *   - UserListService   (COUSR00C.cbl, CICS transaction CU00)
 *   - UserUpdateService (COUSR02C.cbl, CICS transaction CU02)
 *
 * This file implements AAP Section 0.5.1 "New Service Interface Files
 * (CREATE)" and follows the design rules in the folder-level AAP for
 * {@code service/interfaces/}. No business logic is expressed here —
 * this is a pure Java contract (no default methods, no annotations on
 * the type). Exceptions thrown by implementations are Java unchecked
 * exceptions and therefore do NOT appear in {@code throws} clauses,
 * only in Javadoc {@code @throws} tags for documentation.
 *
 * AAP Rule Compliance:
 *   R-001 — No business logic; interface-only declarations
 *   R-004 — COBOL {@see} references preserved for traceability
 *   R-006 — Method signatures match concrete sources exactly
 *           (including {@code Page<?>} wildcard generics on
 *            {@code hasNextPage} and {@code hasPreviousPage})
 *
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 * Licensed under the Apache License, Version 2.0
 */
package com.cardemo.service.interfaces;

import com.cardemo.model.dto.UserSecurityDto;
import org.springframework.data.domain.Page;

/**
 * Service contract for user-security administration operations in the CardDemo application.
 *
 * <p>This interface defines the public service boundary for the admin domain.
 * Implementations are provided by Spring-managed {@code @Service} classes in
 * {@code com.cardemo.service.admin}. Consumers (e.g., {@code UserAdminController})
 * depend on this interface rather than on concrete classes so that alternative
 * implementations may be supplied for testing (mocks) or future extension
 * without modifying the consumer code.</p>
 *
 * <p>The interface aggregates CRUD+list operations across four concrete service
 * classes, each of which migrates one COBOL online program:</p>
 * <ul>
 *   <li>{@code UserAddService}    — create a user (COUSR01C.cbl)</li>
 *   <li>{@code UserDeleteService} — read + delete a user (COUSR03C.cbl)</li>
 *   <li>{@code UserListService}   — paginated browse (COUSR00C.cbl)</li>
 *   <li>{@code UserUpdateService} — read + update a user (COUSR02C.cbl)</li>
 * </ul>
 *
 * <p>The nine methods declared here correspond exactly to the public methods
 * of these four classes. No single concrete class implements all nine methods;
 * each concrete implementation is responsible for its own domain-specific
 * subset. See the package-level README / folder-level AAP note for the
 * implementation strategy (each concrete class implements {@code AdminService}
 * and provides only its own methods in its declared surface).</p>
 *
 * <h3>Transactional Semantics (Implementation Concern)</h3>
 * <p>Transactional behavior is a property of the implementing classes, not of
 * this interface. As documented in the concrete sources:</p>
 * <ul>
 *   <li>{@code UserListService} carries class-level
 *       {@code @Transactional(readOnly = true)}; all of its read operations
 *       ({@link #listUsers(int)}, {@link #listUsersFromId(String, int)},
 *       {@link #hasNextPage(Page)}, {@link #hasPreviousPage(Page)}) inherit
 *       it.</li>
 *   <li>Write operations on {@code UserAddService}, {@code UserDeleteService},
 *       and {@code UserUpdateService} carry method-level {@code @Transactional}
 *       (or {@code @Transactional(readOnly = true)} for read-only methods
 *       such as {@link #getUserForDelete(String)} and
 *       {@link #getUserForUpdate(String)}).</li>
 * </ul>
 *
 * <h3>COBOL Source References</h3>
 * <p>These references provide traceability back to the original mainframe
 * programs (see {@code TRACEABILITY_MATRIX.md} for the full paragraph-level
 * mapping):</p>
 *
 * @see com.cardemo.model.dto.UserSecurityDto
 * @see org.springframework.data.domain.Page
 * @see <a href="file:app/cbl/COUSR00C.cbl">COUSR00C.cbl</a> — User List online transaction (CU00)
 * @see <a href="file:app/cbl/COUSR01C.cbl">COUSR01C.cbl</a> — User Add online transaction (CU01)
 * @see <a href="file:app/cbl/COUSR02C.cbl">COUSR02C.cbl</a> — User Update online transaction (CU02)
 * @see <a href="file:app/cbl/COUSR03C.cbl">COUSR03C.cbl</a> — User Delete online transaction (CU03)
 */
public interface AdminService {

    // ---------------------------------------------------------------------
    // User Add operations (from UserAddService / COUSR01C.cbl)
    // ---------------------------------------------------------------------

    /**
     * Creates a new user-security record with BCrypt-hashed password.
     *
     * <p>Maps COBOL paragraph {@code WRITE-USER-SEC-FILE} (COUSR01C.cbl
     * lines 238-274). The COBOL program originally stored the password in
     * plaintext (SEC-USR-PWD PIC X(08)); the Java migration hashes the
     * password with BCrypt per Decision D-002 before persistence. The
     * returned DTO has its password field sanitized (set to {@code null})
     * to prevent credential leakage across API boundaries.</p>
     *
     * <p>Implementation: {@code UserAddService#addUser(UserSecurityDto)}
     * annotated {@code @Transactional}.</p>
     *
     * @param dto the DTO containing the new user's fields (plaintext
     *            password supplied in the {@code password} / SEC-USR-PWD
     *            field)
     * @return a sanitized {@link UserSecurityDto} representing the created
     *         user (password field is NOT returned)
     * @throws com.cardemo.exception.ValidationException      if any of the
     *         five required fields (first name, last name, user ID,
     *         password, user type) fails validation
     * @throws com.cardemo.exception.DuplicateRecordException if a user
     *         with the same user ID already exists (maps
     *         DFHRESP(DUPKEY)/DFHRESP(DUPREC))
     * @throws RuntimeException                               for unexpected
     *         database or hashing failures (maps COBOL
     *         "Unable to Add User..." error)
     * @see <a href="file:app/cbl/COUSR01C.cbl">COUSR01C.cbl</a>
     *      — WRITE-USER-SEC-FILE paragraph
     */
    UserSecurityDto addUser(UserSecurityDto dto);

    // ---------------------------------------------------------------------
    // User Delete operations (from UserDeleteService / COUSR03C.cbl)
    // ---------------------------------------------------------------------

    /**
     * Retrieves a user record for the pre-delete confirmation view.
     *
     * <p>Maps COBOL paragraph {@code READ-USER-SEC-FILE} (COUSR03C.cbl)
     * used in the display-before-confirm flow. The CU03 delete screen
     * shows user ID, first name, last name, and user type but deliberately
     * omits the password field.</p>
     *
     * <p>Implementation: {@code UserDeleteService#getUserForDelete(String)}
     * annotated {@code @Transactional(readOnly = true)}.</p>
     *
     * @param userId the 8-character user identifier to look up
     * @return the populated {@link UserSecurityDto} with user details for
     *         confirmation display (password field is always {@code null})
     * @throws com.cardemo.exception.ValidationException     if {@code userId}
     *         is blank, {@code null}, or malformed
     * @throws com.cardemo.exception.RecordNotFoundException if no user
     *         matches the given ID (maps DFHRESP(NOTFND))
     * @see <a href="file:app/cbl/COUSR03C.cbl">COUSR03C.cbl</a>
     *      — READ-USER-SEC-FILE paragraph (display-before-confirm)
     */
    UserSecurityDto getUserForDelete(String userId);

    /**
     * Deletes a user-security record by user ID, returning the deleted entity.
     *
     * <p>Maps COBOL paragraph {@code DELETE-USER-SEC-FILE} (COUSR03C.cbl
     * lines 305-336). The COBOL flow re-reads the record with an UPDATE
     * lock before deletion to ensure the record still exists and to
     * acquire an exclusive lock; in the Java migration this isolation is
     * guaranteed by the {@code @Transactional} boundary.</p>
     *
     * <p>Implementation: {@code UserDeleteService#deleteUser(String)}
     * annotated {@code @Transactional}.</p>
     *
     * @param userId the 8-character user identifier of the user to delete
     * @return the deleted {@link UserSecurityDto} with sanitized password
     *         field (always {@code null})
     * @throws com.cardemo.exception.ValidationException     if {@code userId}
     *         is blank, {@code null}, or malformed
     * @throws com.cardemo.exception.RecordNotFoundException if no user
     *         matches the given ID (maps DFHRESP(NOTFND) / unable-to-delete
     *         response code)
     * @see <a href="file:app/cbl/COUSR03C.cbl">COUSR03C.cbl</a>
     *      — DELETE-USER-SEC-FILE paragraph
     */
    UserSecurityDto deleteUser(String userId);

    // ---------------------------------------------------------------------
    // User List operations (from UserListService / COUSR00C.cbl)
    // ---------------------------------------------------------------------

    /**
     * Lists user-security records with pagination, starting from the first
     * user ID in ascending order.
     *
     * <p>Maps COBOL paragraph {@code PROCESS-PAGE-FORWARD} (COUSR00C.cbl
     * lines 282-331) driven by the PROCESS-PF7-PF8-KEYS forward/backward
     * browse logic. The COBOL program uses CICS STARTBR + READNEXT to
     * fetch 10 records per page ({@code USER-REC OCCURS 10 TIMES}); the
     * Java migration uses a single JPA paginated query.</p>
     *
     * <p>Implementation: {@code UserListService#listUsers(int)} inherits
     * class-level {@code @Transactional(readOnly = true)}.</p>
     *
     * @param pageNumber zero-based page number (maps CDEMO-USRLST-PAGE-NUM
     *                   / CDEMO-CU00-PAGE-NUM). Negative values are clamped
     *                   to 0 by the implementation.
     * @return a {@link Page} of {@link UserSecurityDto} entries for the
     *         requested page (page size 10, matching COBOL
     *         {@code USER-REC OCCURS 10 TIMES}); empty page if no users
     *         exist
     * @see <a href="file:app/cbl/COUSR00C.cbl">COUSR00C.cbl</a>
     *      — PROCESS-PF7-PF8-KEYS paragraph (forward browse)
     */
    Page<UserSecurityDto> listUsers(int pageNumber);

    /**
     * Lists user-security records starting from the specified user ID,
     * supporting COBOL-style STARTBR/READNEXT browse.
     *
     * <p>Maps COBOL paragraphs {@code STARTBR-USER-SEC-FILE} (line 586) and
     * {@code READNEXT-USER-SEC-FILE} (line 619) in COUSR00C.cbl. The COBOL
     * STARTBR positions the browse cursor at the first record with a key
     * greater than or equal to the supplied value (GTEQ default); the Java
     * migration implements this via
     * {@code UserSecurityRepository#findBySecUsrIdGreaterThanEqual}.</p>
     *
     * <p>When {@code startUserId} is {@code null} or blank, the
     * implementation delegates to {@link #listUsers(int)} — equivalent to
     * the COBOL handling of USRIDINI = SPACES OR LOW-VALUES.</p>
     *
     * <p>Implementation: {@code UserListService#listUsersFromId(String, int)}
     * inherits class-level {@code @Transactional(readOnly = true)}.</p>
     *
     * @param startUserId the user ID to start the browse from (inclusive /
     *                    GTEQ); if {@code null} or blank, browse starts
     *                    from the first record
     * @param pageNumber  zero-based page number. Negative values are
     *                    clamped to 0 by the implementation.
     * @return a {@link Page} of {@link UserSecurityDto} entries matching
     *         the filter (page size 10); empty page if no users match
     * @see <a href="file:app/cbl/COUSR00C.cbl">COUSR00C.cbl</a>
     *      — STARTBR-USER-SEC-FILE and READNEXT paragraphs
     */
    Page<UserSecurityDto> listUsersFromId(String startUserId, int pageNumber);

    /**
     * Determines whether additional pages exist beyond the given page.
     *
     * <p>Maps the COBOL evaluation of {@code CDEMO-USRLST-NEXT-PAGE-FLG}
     * (COUSR00C.cbl), which the original program sets by issuing one
     * extra READNEXT after the 10-record chunk to detect end-of-file.
     * In the Java migration, {@link Page#hasNext()} provides this
     * information directly from the JPA count query, without the need
     * for an extra read.</p>
     *
     * <p>Used by the controller to decide whether to enable the PF8
     * (page-forward) navigation indicator in the response metadata.</p>
     *
     * <p><strong>Signature note:</strong> the parameter is a wildcard
     * {@code Page<?>} (matching the concrete source) so that callers may
     * pass any page — {@code Page<UserSecurityDto>} in this domain, but
     * the method remains usable for other page element types.</p>
     *
     * @param page the current page of results (any element type)
     * @return {@code true} if at least one more page exists;
     *         {@code false} otherwise
     * @see <a href="file:app/cbl/COUSR00C.cbl">COUSR00C.cbl</a>
     *      — evaluation of CDEMO-USRLST-NEXT-PAGE-FLG
     */
    boolean hasNextPage(Page<?> page);

    /**
     * Determines whether previous pages exist before the given page.
     *
     * <p>Maps the COBOL evaluation of the prior-page flag in COUSR00C.cbl's
     * {@code PROCESS-PF7-KEY} paragraph (lines 248-255), which checks
     * {@code CDEMO-CU00-PAGE-NUM > 1} before allowing PF7 (page-backward)
     * navigation. In the Java migration, {@link Page#hasPrevious()}
     * provides this check directly — returns {@code false} for page 0
     * (equivalent to COBOL page 1).</p>
     *
     * <p><strong>Signature note:</strong> the parameter is a wildcard
     * {@code Page<?>} (matching the concrete source) so that callers may
     * pass any page element type.</p>
     *
     * @param page the current page of results (any element type)
     * @return {@code true} if the current page is not the first;
     *         {@code false} otherwise
     * @see <a href="file:app/cbl/COUSR00C.cbl">COUSR00C.cbl</a>
     *      — evaluation of prior-page flag (CDEMO-CU00-PAGE-NUM > 1)
     */
    boolean hasPreviousPage(Page<?> page);

    // ---------------------------------------------------------------------
    // User Update operations (from UserUpdateService / COUSR02C.cbl)
    // ---------------------------------------------------------------------

    /**
     * Retrieves a user record for the update view, populating the update
     * DTO with current state.
     *
     * <p>Maps COBOL paragraph {@code READ-USER-SEC-FILE} (COUSR02C.cbl
     * lines 320-353). The CU02 update screen originally displayed the
     * plaintext password (MOVE SEC-USR-PWD TO PASSWDI OF COUSR2AI); the
     * Java migration NEVER returns the BCrypt hash — the password field
     * in the returned DTO is always {@code null}.</p>
     *
     * <p>Implementation: {@code UserUpdateService#getUserForUpdate(String)}
     * annotated {@code @Transactional(readOnly = true)}.</p>
     *
     * @param userId the 8-character user identifier to look up (maps COBOL
     *               {@code USRIDINI OF COUSR2AI})
     * @return the populated {@link UserSecurityDto} containing the user's
     *         current data (password is always {@code null})
     * @throws com.cardemo.exception.ValidationException     if {@code userId}
     *         is blank, {@code null}, or malformed (message:
     *         "User ID can NOT be empty...")
     * @throws com.cardemo.exception.RecordNotFoundException if no user
     *         matches the given ID (message: "User ID NOT found...",
     *         maps DFHRESP(NOTFND))
     * @see <a href="file:app/cbl/COUSR02C.cbl">COUSR02C.cbl</a>
     *      — READ-USER-SEC-FILE paragraph
     */
    UserSecurityDto getUserForUpdate(String userId);

    /**
     * Applies updates to the specified user's security record, re-hashing
     * the password if changed.
     *
     * <p>Maps COBOL paragraph {@code UPDATE-USER-SEC-FILE} / UPDATE-USER-INFO
     * (COUSR02C.cbl lines 177-245). The processing flow is:</p>
     * <ol>
     *   <li>Validate all five fields in strict COBOL order
     *       (USERID → FNAME → LNAME → PASSWORD → USERTYPE)</li>
     *   <li>Re-read the current record for change-detection baseline</li>
     *   <li>Compare each input field against the stored value; for
     *       password, use BCrypt {@code matches()} instead of plaintext
     *       equality (security upgrade per Decision D-002)</li>
     *   <li>If at least one field changed, persist via
     *       {@code repository.save()} (REWRITE equivalent); if no changes,
     *       throw {@code ValidationException("Please modify to update ...")}</li>
     * </ol>
     *
     * <p>Implementation: {@code UserUpdateService#updateUser(String, UserSecurityDto)}
     * annotated {@code @Transactional}.</p>
     *
     * @param userId the 8-character user identifier of the user to update
     * @param dto    the DTO containing new field values from the client
     * @return the updated {@link UserSecurityDto} (sanitized — password
     *         is always {@code null})
     * @throws com.cardemo.exception.ValidationException     if any field
     *         fails validation or no fields have been modified
     * @throws com.cardemo.exception.RecordNotFoundException if the user
     *         does not exist (maps DFHRESP(NOTFND))
     * @see <a href="file:app/cbl/COUSR02C.cbl">COUSR02C.cbl</a>
     *      — UPDATE-USER-SEC-FILE paragraph
     */
    UserSecurityDto updateUser(String userId, UserSecurityDto dto);
}
