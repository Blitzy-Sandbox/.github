/*
 * AuthService.java — Authentication Service Interface
 *
 * Public service contract for online authentication/sign-on workflows in
 * the CardDemo application. Wraps the single public method of the concrete
 * {@code @Service} class
 * {@code com.cardemo.service.auth.AuthenticationService} which migrates
 * COBOL program COSGN00C.cbl (CICS transaction code CC00/CSGN).
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
 *   R-004 — COBOL {@see} reference preserved for traceability
 *   R-006 — Method signature matches concrete source exactly
 *           ({@code SignOnResponse authenticate(SignOnRequest request)}
 *            at AuthenticationService.java line 243)
 *
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 * Licensed under the Apache License, Version 2.0
 */
package com.cardemo.service.interfaces;

import com.cardemo.model.dto.SignOnRequest;
import com.cardemo.model.dto.SignOnResponse;

/**
 * Service contract for authentication and sign-on operations in the CardDemo application.
 *
 * <p>This interface defines the public service boundary for the authentication
 * domain. The implementation is provided by the Spring-managed
 * {@code @Service} class
 * {@code com.cardemo.service.auth.AuthenticationService}, which migrates the
 * legacy COBOL program {@code COSGN00C.cbl} (CICS transaction codes
 * {@code CC00}/{@code CSGN}). Consumers (e.g., {@code AuthController})
 * depend on this interface rather than on the concrete class so that
 * alternative implementations may be supplied for testing (mocks) or future
 * extension without modifying the consumer code.</p>
 *
 * <p>The interface represents the online sign-on transaction flow — the
 * single entry point of the CardDemo application for end users. The
 * implementation orchestrates the following steps atomically:</p>
 * <ol>
 *   <li>Validate that the user ID and password fields on the request are
 *       non-null and non-blank (maps COSGN00C.cbl {@code PROCESS-ENTER-KEY}
 *       {@code EVALUATE TRUE} at lines 117-130: "Please enter User ID ..."
 *       and "Please enter Password ...").</li>
 *   <li>Normalize the user ID and password by trimming whitespace and
 *       converting to uppercase (maps COBOL
 *       {@code MOVE FUNCTION UPPER-CASE(USERIDI) TO WS-USER-ID} and
 *       {@code MOVE FUNCTION UPPER-CASE(PASSWDI) TO WS-USER-PWD} at
 *       lines 132-136). COBOL uppercased passwords before both storage
 *       and comparison, so BCrypt hashes were generated from uppercased
 *       passwords — this normalization preserves behavioral parity.</li>
 *   <li>Retrieve the {@code UserSecurity} entity from the USRSEC table
 *       by the normalized user ID (maps {@code READ-USER-SEC-FILE} at
 *       lines 211-219: {@code EXEC CICS READ DATASET('USRSEC')
 *       RIDFLD(WS-USER-ID)}).</li>
 *   <li>Verify the supplied password against the stored BCrypt hash via
 *       Spring Security's {@code BCryptPasswordEncoder} (upgrades the
 *       COBOL plaintext comparison {@code IF SEC-USR-PWD = WS-USER-PWD}
 *       at line 223 per architectural decision C-003).</li>
 *   <li>Derive the target routing information based on the user type:
 *       {@code ADMIN} users route to the admin menu
 *       ({@code COADM01C}/{@code CA00}) while regular {@code USER}s route
 *       to the main menu ({@code COMEN01C}/{@code CM01}) — preserving
 *       COBOL's {@code EXEC CICS XCTL PROGRAM(...)} routing at lines
 *       224-240.</li>
 *   <li>Populate and return a {@link SignOnResponse} containing the
 *       session token (UUID/JWT replacing CICS COMMAREA state), user type,
 *       user ID, and routing metadata.</li>
 * </ol>
 *
 * <h3>Security Upgrade C-003 (Implementation Concern)</h3>
 * <p>The COBOL program stored and compared passwords in plaintext
 * ({@code SEC-USR-PWD = WS-USER-PWD}). The Java implementation upgrades
 * to BCrypt password hashing via Spring Security's
 * {@code BCryptPasswordEncoder.matches()}, which performs a constant-time
 * cryptographic comparison. Callers of this interface must never log raw
 * password values, and the {@link SignOnRequest#toString()} representation
 * intentionally omits the password field to prevent credential leakage
 * in diagnostic output. The returned {@link SignOnResponse#toString()}
 * similarly omits the session token for the same reason.</p>
 *
 * <h3>Stateless REST Boundary (Implementation Concern)</h3>
 * <p>This interface represents the replacement of the CICS
 * pseudo-conversational sign-on flow (terminal 3270, COMMAREA state)
 * with a stateless REST-compatible authentication mechanism. The
 * implementation does not maintain session state in-process; it issues a
 * fresh token on each successful authentication, and subsequent REST
 * invocations present the token via HTTP Basic Auth headers per
 * architectural decision D-011 (HTTP Basic Auth).</p>
 *
 * <h3>COBOL Source Reference</h3>
 * <p>This reference provides traceability back to the original mainframe
 * program (see {@code TRACEABILITY_MATRIX.md} for the full paragraph-level
 * mapping).</p>
 *
 * @see com.cardemo.model.dto.SignOnRequest
 * @see com.cardemo.model.dto.SignOnResponse
 * @see <a href="file://app/cbl/COSGN00C.cbl">COSGN00C.cbl</a> — Online Sign-On
 *      transaction (CICS transaction codes {@code CC00}/{@code CSGN})
 */
public interface AuthService {

    /**
     * Authenticates a user given a sign-on request, returning a sign-on
     * response containing session context and role information.
     *
     * <p>This method performs the complete sign-on orchestration: it
     * validates that the request contains a non-blank user ID and password,
     * normalizes both fields by trimming and uppercasing (preserving COBOL
     * {@code FUNCTION UPPER-CASE} semantics), retrieves the
     * {@code UserSecurity} entity from the USRSEC table by user ID,
     * verifies the supplied password against the stored BCrypt hash using
     * Spring Security's {@code BCryptPasswordEncoder}, and constructs a
     * populated {@link SignOnResponse} containing the session token, user
     * type, user ID, and target transaction / program routing metadata
     * derived from the user type (ADMIN → {@code COADM01C}/{@code CA00};
     * USER → {@code COMEN01C}/{@code CM01}).</p>
     *
     * <p>Key COBOL paragraph mappings:</p>
     * <ul>
     *   <li>{@code PROCESS-ENTER-KEY} (COSGN00C.cbl lines 117-209) —
     *       input validation, uppercase normalization, and orchestration
     *       of the read-verify-route sequence</li>
     *   <li>{@code READ-USER-SEC-FILE} (lines 211-219) — USRSEC VSAM
     *       keyed-read replaced by JPA repository lookup</li>
     *   <li>Password comparison at line 223 — COBOL plaintext
     *       {@code SEC-USR-PWD = WS-USER-PWD} upgraded to BCrypt
     *       cryptographic comparison per constraint C-003</li>
     *   <li>{@code EXEC CICS XCTL} at lines 232-240 — CICS
     *       program-transfer routing replaced by {@code toTranId} and
     *       {@code toProgram} fields on the response DTO</li>
     * </ul>
     *
     * <p>The implementation does not mutate database state on a successful
     * authentication (the USRSEC read is non-updating) and therefore does
     * not carry a {@code @Transactional} annotation on the corresponding
     * concrete method; observability hooks (the
     * {@code carddemo.auth.attempts} Micrometer counter) are invoked
     * inside the implementation as side-effecting telemetry calls and are
     * not visible through this interface.</p>
     *
     * @param request the sign-on request DTO containing the user ID
     *                (max 8 characters, mapping COBOL
     *                {@code USERIDI PIC X(8)} from COSGN00.CPY line 72) and
     *                plaintext password (max 128 characters, mapping
     *                {@code PASSWDI PIC X(8)} from COSGN00.CPY line 78);
     *                must be non-null and both fields must be non-blank
     * @return the populated {@link SignOnResponse} containing the session
     *         token (UUID/JWT replacing CICS COMMAREA state), user type
     *         (ADMIN/USER matching COBOL {@code CDEMO-USRTYP} 88-level
     *         conditions), user ID ({@code CDEMO-USER-ID PIC X(08)}),
     *         target transaction ID ({@code CDEMO-TO-TRANID PIC X(04)}),
     *         and target program name ({@code CDEMO-TO-PROGRAM PIC X(08)})
     *         corresponding to the authenticated user's landing endpoint
     * @throws IllegalArgumentException if the request is null, or the
     *         user ID is blank/null (maps COSGN00C.cbl line 120
     *         "Please enter User ID ..."), or the password is blank/null
     *         (maps line 126 "Please enter Password ...")
     * @throws org.springframework.security.authentication.BadCredentialsException
     *         if the supplied password does not match the stored BCrypt
     *         hash for the located user (maps COSGN00C.cbl line 226
     *         "Wrong Password. Try again ..." — the COBOL plaintext
     *         comparison {@code SEC-USR-PWD = WS-USER-PWD} mismatch
     *         branch, upgraded to BCrypt per constraint C-003)
     * @throws com.cardemo.exception.RecordNotFoundException if no user
     *         record matches the supplied user ID in the USRSEC table
     *         (maps COSGN00C.cbl line 216 {@code RESP(13) DFHRESP(NOTFND)}
     *         on the {@code READ-USER-SEC-FILE} paragraph: "User not
     *         found. Try again ...")
     * @see <a href="file://app/cbl/COSGN00C.cbl">COSGN00C.cbl</a>
     *      {@code PROCESS-ENTER-KEY} and {@code READ-USER-SEC-FILE}
     *      paragraphs
     */
    SignOnResponse authenticate(SignOnRequest request);
}
