/*
 * AccountService.java — Account Domain Service Interface
 *
 * Public service contract for account-domain operations in the CardDemo
 * application. Aggregates the public methods of the two concrete
 * {@code @Service} classes under {@code com.cardemo.service.account}:
 *   - AccountViewService   (COACTVWC.cbl, CICS transaction CAVW — read-only view)
 *   - AccountUpdateService (COACTUPC.cbl, CICS transaction CAUP — read-for-update + write)
 *
 * This file implements AAP Section 0.5.1 "New Service Interface Files
 * (CREATE)" and follows the design rules in the folder-level AAP for
 * {@code service/interfaces/}. No business logic is expressed here —
 * this is a pure Java contract (no default methods, no annotations on
 * the type). Exceptions thrown by implementations are Java unchecked
 * exceptions and therefore do NOT appear in {@code throws} clauses,
 * only in Javadoc {@code @throws} tags for documentation.
 *
 * The interface aggregates read ({@code getAccountView}, {@code getAccount})
 * and write ({@code updateAccount}) operations across two concrete service
 * classes. No single concrete class implements all three methods; each
 * concrete implementation is responsible for its own domain-specific subset.
 * Consumers such as {@code AccountController} depend on this aggregated
 * interface rather than on concrete classes so that alternative
 * implementations may be supplied for testing (mocks) or future extension
 * without modifying consumer code.
 *
 * AAP Rule Compliance:
 *   R-001 — No business logic; interface-only declarations
 *   R-004 — COBOL {@see} references preserved for traceability
 *   R-006 — Method signatures match concrete sources exactly
 *           ({@code AccountDto getAccountView(String)} at
 *            AccountViewService.java line 121;
 *            {@code AccountDto getAccount(String)} at
 *            AccountUpdateService.java line 115;
 *            {@code AccountDto updateAccount(String, AccountDto)} at
 *            AccountUpdateService.java line 167)
 *
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 * Licensed under the Apache License, Version 2.0
 */
package com.cardemo.service.interfaces;

import com.cardemo.model.dto.AccountDto;

/**
 * Service contract for account-domain operations in the CardDemo application.
 *
 * <p>This interface defines the public service boundary for the account domain.
 * Implementations are provided by Spring-managed {@code @Service} classes in
 * {@code com.cardemo.service.account}. Consumers (e.g., {@code AccountController})
 * depend on this interface rather than on concrete classes so that
 * alternative implementations may be supplied for testing (mocks) or future
 * extension without modifying the consumer code.</p>
 *
 * <p>The interface aggregates read ({@link #getAccountView(String)},
 * {@link #getAccount(String)}) and write ({@link #updateAccount(String, AccountDto)})
 * operations for account entities. The two read methods serve different
 * use cases:</p>
 * <ul>
 *   <li>{@link #getAccountView(String)} is the read-only account detail view
 *       used by the CAVW (Account View) online transaction. It joins account,
 *       card cross-reference, and customer tables to produce a composite
 *       DTO for display.</li>
 *   <li>{@link #getAccount(String)} is the read-for-update pre-fetch used by
 *       the CAUP (Account Update) online transaction. It populates the
 *       account-update DTO with the current entity state plus the JPA
 *       {@code @Version} concurrency token so the client can later submit
 *       modifications via {@link #updateAccount(String, AccountDto)}.</li>
 * </ul>
 *
 * <h3>Transactional Semantics (Implementation Concern)</h3>
 * <p>Transactional behavior is a property of the implementing classes, not
 * of this interface. As documented in the concrete sources:</p>
 * <ul>
 *   <li>{@code AccountViewService#getAccountView(String)} carries method-level
 *       {@code @Transactional(readOnly = true)} — the multi-dataset read chain
 *       (cross-reference → account → customer) runs in a single read-only
 *       transaction for consistency.</li>
 *   <li>{@code AccountUpdateService#getAccount(String)} has no method-level
 *       {@code @Transactional} annotation; it inherits the container default
 *       (no transaction) and performs a sequence of reads used solely to
 *       populate the pre-update DTO.</li>
 *   <li>{@code AccountUpdateService#updateAccount(String, AccountDto)} carries
 *       method-level {@code @Transactional(rollbackFor = Exception.class)}
 *       — all field mutations on both the Account and Customer entities are
 *       persisted atomically, mapping the COBOL CICS SYNCPOINT ROLLBACK
 *       semantics of COACTUPC.cbl so that a failure after a partial REWRITE
 *       rolls back the entire update.</li>
 * </ul>
 *
 * <h3>Optimistic Locking Protocol (Update Flow)</h3>
 * <p>The read-for-update and update methods together implement the COBOL CICS
 * {@code READ UPDATE} + snapshot-comparison pattern from COACTUPC.cbl
 * paragraph {@code 9700-CHECK-CHANGE-IN-REC} via JPA {@code @Version}:</p>
 * <ol>
 *   <li>Client calls {@link #getAccount(String)} to fetch the current record
 *       plus version token (analogous to the COBOL "before image" snapshot
 *       captured by the 9000-READ-ACCT chain).</li>
 *   <li>Client mutates the returned {@link AccountDto} and submits it via
 *       {@link #updateAccount(String, AccountDto)}.</li>
 *   <li>Implementation compares the submitted version to the current DB
 *       version; on mismatch it throws
 *       {@link com.cardemo.exception.ConcurrentModificationException}
 *       (HTTP 409 Conflict), matching the COBOL "record changed by another
 *       user" semantics.</li>
 * </ol>
 *
 * <h3>Decimal Precision Constraint (Implementation Concern)</h3>
 * <p>All monetary fields on {@link AccountDto} (current balance, credit
 * limits, cycle credit/debit totals) use {@link java.math.BigDecimal}
 * exclusively — zero {@code float}/{@code double} substitution per AAP
 * Section 0.8.2. The implementation preserves COBOL COMP-3 packed decimal
 * semantics at scale 2.</p>
 *
 * <h3>COBOL Source References</h3>
 * <p>These references provide traceability back to the original mainframe
 * programs (see {@code TRACEABILITY_MATRIX.md} for the full paragraph-level
 * mapping):</p>
 *
 * @see <a href="file:app/cbl/COACTVWC.cbl">COACTVWC.cbl</a>
 *      — Account View online transaction (CICS transaction code {@code CAVW})
 * @see <a href="file:app/cbl/COACTUPC.cbl">COACTUPC.cbl</a>
 *      — Account Update online transaction (CICS transaction code {@code CAUP})
 * @see AccountDto
 * @see com.cardemo.service.account.AccountViewService
 * @see com.cardemo.service.account.AccountUpdateService
 */
public interface AccountService {

    // ---------------------------------------------------------------------
    // Account View operations (from AccountViewService / COACTVWC.cbl)
    // ---------------------------------------------------------------------

    /**
     * Retrieves the account detail view for the specified account ID.
     *
     * <p>Performs a multi-dataset read chain — joining the account, card
     * cross-reference, and customer tables — to produce a read-only composite
     * {@link AccountDto} for display by the CAVW (Account View) online
     * transaction. Maps the COBOL {@code 9000-READ-ACCT} paragraph chain of
     * COACTVWC.cbl, which orchestrates three sequential reads:</p>
     * <ol>
     *   <li>{@code 9200-GETCARDXREF-BYACCT} — cross-reference lookup via the
     *       {@code CXACAIX} alternate index to resolve the customer ID.</li>
     *   <li>{@code 9300-GETACCTDATA-BYACCT} — account master data read by
     *       primary key from the {@code ACCTDAT} dataset.</li>
     *   <li>{@code 9400-GETCUSTDATA-BYCUST} — customer master data read by
     *       customer ID from the {@code CUSTDAT} dataset.</li>
     * </ol>
     *
     * <p>Input validation maps COBOL paragraph {@code 2210-EDIT-ACCOUNT},
     * enforcing non-null, non-blank, numeric, non-zero, exactly 11-digit
     * account IDs. DTO assembly maps COBOL paragraph
     * {@code 1200-SETUP-SCREEN-VARS}, populating account and customer fields
     * from their respective entities into a single {@link AccountDto}.</p>
     *
     * <p>Implementation: {@code AccountViewService#getAccountView(String)}
     * runs under method-level {@code @Transactional(readOnly = true)}.</p>
     *
     * @param acctId the 11-digit numeric account identifier (maps COBOL
     *               {@code PIC 9(11)}); must be non-null, non-blank, numeric,
     *               non-zero, and exactly 11 digits
     * @return the populated {@link AccountDto} containing combined account,
     *         card cross-reference, and customer details
     * @throws IllegalArgumentException if {@code acctId} fails validation
     *         (null, blank, non-numeric, all zeros, or wrong length) —
     *         maps COBOL paragraph {@code 2210-EDIT-ACCOUNT} edit failure
     * @throws com.cardemo.exception.RecordNotFoundException if the card
     *         cross-reference, account, or customer record is not found
     *         in the database (maps COBOL FILE STATUS 23 /
     *         {@code DFHRESP(NOTFND)})
     * @see <a href="file:app/cbl/COACTVWC.cbl">COACTVWC.cbl</a>
     *      — {@code PROCESS-ENTER-KEY} / {@code 9000-READ-ACCT} paragraph
     *      chain
     */
    AccountDto getAccountView(String acctId);

    // ---------------------------------------------------------------------
    // Account Update operations (from AccountUpdateService / COACTUPC.cbl)
    // ---------------------------------------------------------------------

    /**
     * Retrieves the account for subsequent update, populating the
     * account-update DTO with current entity state and the concurrency token.
     *
     * <p>This is the read-for-update pre-fetch step of the account-update
     * workflow: the result DTO is displayed and modified by the client, then
     * passed back to {@link #updateAccount(String, AccountDto)} along with
     * the unchanged JPA {@code @Version} token for optimistic concurrency
     * control. Maps COBOL paragraph {@code 9000-READ-ACCT} of COACTUPC.cbl,
     * which performs a 3-step read chain:</p>
     * <ol>
     *   <li>{@code 9100-GETACCT-REQUEST} / cross-reference lookup to resolve
     *       the customer ID via the {@code CXACAIX} alternate index path.</li>
     *   <li>{@code 9200-GETCARDXREF-REQUEST} — CICS
     *       {@code READ FILE(ACCTFILENAME)} for account record.</li>
     *   <li>{@code 9400-GETCUSTDATA} — CICS {@code READ FILE(CUSTFILENAME)}
     *       for customer record by customer ID.</li>
     * </ol>
     *
     * <p>The returned {@link AccountDto} carries the full current account and
     * customer fields plus the JPA {@code @Version} token populated from the
     * {@code Account} entity — the client must echo this version token back
     * on {@link #updateAccount(String, AccountDto)} to detect concurrent
     * modifications.</p>
     *
     * <p>Implementation: {@code AccountUpdateService#getAccount(String)} has
     * no explicit method-level {@code @Transactional} annotation; it inherits
     * the container default and performs a sequence of repository reads used
     * solely to populate the pre-update DTO.</p>
     *
     * @param acctId the 11-digit numeric account identifier (maps COBOL
     *               {@code ACCT-ID PIC 9(11)} from CVACT01Y.cpy)
     * @return the populated {@link AccountDto} with current account and
     *         customer data plus the optimistic-locking version token
     * @throws com.cardemo.exception.RecordNotFoundException if no account,
     *         cross-reference, or customer record matches (maps COBOL
     *         FILE STATUS 23 / {@code DFHRESP(NOTFND)})
     * @throws com.cardemo.exception.ValidationException if {@code acctId}
     *         is structurally invalid (maps COBOL paragraph
     *         {@code 2210-EDIT-ACCOUNT} edit failure)
     * @see <a href="file:app/cbl/COACTUPC.cbl">COACTUPC.cbl</a>
     *      — {@code READ-ACCT-INFO} / {@code 9000-READ-ACCT} paragraph
     *      (initial read)
     */
    AccountDto getAccount(String acctId);

    /**
     * Persists updates to the account with optimistic locking and cascading
     * updates to the associated customer entity.
     *
     * <p>Performs the full account update with atomic dual-dataset persistence
     * — validates all 25+ fields (FICO range, date invariants, state/zip
     * prefix consistency, SSN/phone format, monetary field scale), enforces
     * optimistic concurrency via JPA {@code @Version}, and applies changes to
     * both the {@code Account} and {@code Customer} entities atomically. Maps
     * the COBOL {@code PROCESS-UPDATE-ACCT} → {@code 9600-WRITE-PROCESSING}
     * paragraph chain of COACTUPC.cbl.</p>
     *
     * <p>The {@code @Transactional(rollbackFor = Exception.class)} annotation
     * on the implementation maps the COBOL EXEC CICS SYNCPOINT ROLLBACK
     * semantics — if the customer REWRITE fails after the account REWRITE
     * succeeds, the entire transaction is rolled back. Optimistic concurrency
     * control is enforced via JPA {@code @Version} on the {@code Account}
     * entity, mapping COACTUPC paragraph {@code 9700-CHECK-CHANGE-IN-REC}
     * ({@code DATA-WAS-CHANGED-BEFORE-UPDATE}).</p>
     *
     * <p>Key COBOL paragraph mappings:</p>
     * <ul>
     *   <li>{@code 1200-EDIT-MAP-INPUTS} — field-level validation cascade
     *       (25+ fields, error aggregation)</li>
     *   <li>{@code PROCESS-UPDATE-ACCT} — orchestration entry point</li>
     *   <li>{@code 9600-WRITE-PROCESSING} — REWRITE of ACCTDAT and CUSTDAT
     *       under CICS READ UPDATE semantics</li>
     *   <li>{@code 9700-CHECK-CHANGE-IN-REC} — optimistic concurrency check
     *       (before-image snapshot comparison)</li>
     * </ul>
     *
     * <p>Implementation: {@code AccountUpdateService#updateAccount(String, AccountDto)}
     * runs under method-level {@code @Transactional(rollbackFor = Exception.class)}.
     * On successful persistence, the returned DTO carries the incremented
     * JPA {@code @Version} token — the client must use this updated token on
     * any subsequent update to the same account.</p>
     *
     * @param acctId      the 11-digit numeric account identifier of the
     *                    account to update (maps COBOL
     *                    {@code ACCT-ID PIC 9(11)})
     * @param updatedData the {@link AccountDto} containing the new field
     *                    values and the optimistic-locking version token
     *                    previously obtained from {@link #getAccount(String)}
     * @return the refreshed {@link AccountDto} after update, with the
     *         incremented version token reflecting the post-save state
     * @throws com.cardemo.exception.ValidationException if any field fails
     *         validation (maps the {@code 1200-EDIT-MAP-INPUTS} 25+ field
     *         cascade)
     * @throws com.cardemo.exception.RecordNotFoundException if the account,
     *         cross-reference, or customer record does not exist (maps COBOL
     *         FILE STATUS 23 / {@code DFHRESP(NOTFND)})
     * @throws com.cardemo.exception.ConcurrentModificationException if the
     *         version token has changed since pre-fetch (optimistic locking
     *         failure — maps COACTUPC paragraph {@code 9700-CHECK-CHANGE-IN-REC}
     *         {@code DATA-WAS-CHANGED-BEFORE-UPDATE} condition)
     * @see <a href="file:app/cbl/COACTUPC.cbl">COACTUPC.cbl</a>
     *      — {@code PROCESS-UPDATE-ACCT} / {@code 9600-WRITE-PROCESSING} /
     *      {@code 9700-CHECK-CHANGE-IN-REC} paragraphs
     */
    AccountDto updateAccount(String acctId, AccountDto updatedData);
}
