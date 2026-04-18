/*
 * CardService.java — Card Domain Service Interface
 *
 * Public service contract for credit card domain operations in the CardDemo
 * application. Aggregates the public methods of the three concrete
 * {@code @Service} classes under {@code com.cardemo.service.card}:
 *   - CardDetailService (COCRDSLC.cbl, CICS transaction CCDL — detail view)
 *   - CardListService   (COCRDLIC.cbl, CICS transaction CCLI — paginated list)
 *   - CardUpdateService (COCRDUPC.cbl, CICS transaction CCUP — update)
 *
 * This file implements AAP Section 0.5.1 "New Service Interface Files
 * (CREATE)" and follows the design rules in the folder-level AAP for
 * {@code service/interfaces/}. No business logic is expressed here —
 * this is a pure Java contract (no default methods, no annotations on
 * the type). Exceptions thrown by implementations are Java unchecked
 * exceptions and therefore do NOT appear in {@code throws} clauses,
 * only in Javadoc {@code @throws} tags for documentation.
 *
 * The interface aggregates read (list, detail lookup) and write (update)
 * operations across three concrete service classes. No single concrete
 * class implements all six methods; each concrete implementation is
 * responsible for its own domain-specific subset. Consumers such as
 * {@code CardController} depend on this aggregated interface rather than
 * on concrete classes so that alternative implementations may be supplied
 * for testing (mocks) or future extension without modifying consumer code.
 *
 * AAP Rule Compliance:
 *   R-001 — No business logic; interface-only declarations
 *   R-004 — COBOL {@see} references preserved for traceability
 *   R-006 — Method signatures match concrete sources exactly
 *           (including List<CardDto> return type on
 *            {@link #getCardsByAccountId(String)} which reflects the
 *            NONUNIQUEKEY semantics of the COBOL CARDAIX alternate index
 *            — NOT a paginated Page<CardDto>)
 *
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 * Licensed under the Apache License, Version 2.0
 */
package com.cardemo.service.interfaces;

import com.cardemo.model.dto.CardDto;
import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Service contract for credit card domain operations in the CardDemo application.
 *
 * <p>This interface defines the public service boundary for the card domain.
 * Implementations are provided by Spring-managed {@code @Service} classes in
 * {@code com.cardemo.service.card}. Consumers (e.g., {@code CardController})
 * depend on this interface rather than on concrete classes so that
 * alternative implementations may be supplied for testing (mocks) or future
 * extension without modifying the consumer code.</p>
 *
 * <p>The interface aggregates detail lookup (read), paginated list (read),
 * and update (write) operations across three concrete service classes, each
 * of which migrates one COBOL online program:</p>
 * <ul>
 *   <li>{@code CardDetailService} — read a single card by number, or read
 *       all cards for an account (COCRDSLC.cbl, CCDL)</li>
 *   <li>{@code CardListService}   — paginated browse with optional account
 *       and card-number filters (COCRDLIC.cbl, CCLI)</li>
 *   <li>{@code CardUpdateService} — read-for-update pre-fetch and
 *       update-with-optimistic-locking (COCRDUPC.cbl, CCUP)</li>
 * </ul>
 *
 * <p>The six methods declared here correspond exactly to the public methods
 * of these three classes. No single concrete class implements all six
 * methods; each concrete implementation is responsible for its own
 * domain-specific subset. See the folder-level AAP note for the
 * implementation strategy.</p>
 *
 * <h3>Return-Type Rationale</h3>
 * <p>Five of the six methods return {@link CardDto} or a {@link Page} of
 * {@link CardDto}. The single exception is
 * {@link #getCardsByAccountId(String)} which returns a plain
 * {@link java.util.List} rather than a {@link Page}: this method maps the
 * COBOL COCRDSLC.cbl paragraph {@code 9150-GETCARD-BYACCT} that reads the
 * non-unique alternate index {@code CARDAIX} (KEYS 11 16, NONUNIQUEKEY).
 * The result is the complete set of cards associated with the account and
 * is not expected to exceed a small bound (typically 1–5 cards per
 * account), so no pagination is required.</p>
 *
 * <h3>Transactional Semantics (Implementation Concern)</h3>
 * <p>Transactional behavior is a property of the implementing classes, not
 * of this interface. As documented in the concrete sources:</p>
 * <ul>
 *   <li>{@code CardDetailService} carries class-level
 *       {@code @Transactional(readOnly = true)} — both
 *       {@link #getCardDetail(String)} and
 *       {@link #getCardsByAccountId(String)} run in read-only
 *       transactions.</li>
 *   <li>{@code CardListService} carries class-level
 *       {@code @Transactional(readOnly = true)} — both
 *       {@link #listCards(int, String, String)} and
 *       {@link #listCardsByAccount(String, int)} run in read-only
 *       transactions.</li>
 *   <li>{@code CardUpdateService#getCardForUpdate(String)} carries
 *       method-level {@code @Transactional(readOnly = true)} — performs a
 *       read-only pre-fetch that populates the update DTO with the current
 *       JPA {@code @Version} token.</li>
 *   <li>{@code CardUpdateService#updateCard(String, CardDto)} carries
 *       method-level {@code @Transactional} (read-write) — applies field
 *       updates atomically under JPA {@code @Version} optimistic locking.</li>
 * </ul>
 *
 * <h3>Optimistic Locking Protocol (Update Flow)</h3>
 * <p>The update methods together implement the COBOL CICS
 * {@code READ UPDATE} + snapshot-comparison pattern from COCRDUPC.cbl
 * paragraph {@code 9300-CHECK-CHANGE-IN-REC} via JPA {@code @Version}:</p>
 * <ol>
 *   <li>Client calls {@link #getCardForUpdate(String)} to fetch the current
 *       record plus version token (analogous to the COBOL "before image"
 *       snapshot).</li>
 *   <li>Client mutates the returned {@link CardDto} and submits it via
 *       {@link #updateCard(String, CardDto)}.</li>
 *   <li>Implementation compares the submitted version to the current DB
 *       version; on mismatch it throws
 *       {@link com.cardemo.exception.ConcurrentModificationException}
 *       (HTTP 409 Conflict), matching the COBOL "record changed by another
 *       user" semantics.</li>
 * </ol>
 *
 * <h3>COBOL Source References</h3>
 * <p>These references provide traceability back to the original mainframe
 * programs (see {@code TRACEABILITY_MATRIX.md} for the full paragraph-level
 * mapping):</p>
 *
 * @see <a href="file:app/cbl/COCRDLIC.cbl">COCRDLIC.cbl</a>
 *      — Credit Card List online transaction (CCLI)
 * @see <a href="file:app/cbl/COCRDSLC.cbl">COCRDSLC.cbl</a>
 *      — Credit Card Detail View online transaction (CCDL)
 * @see <a href="file:app/cbl/COCRDUPC.cbl">COCRDUPC.cbl</a>
 *      — Credit Card Update online transaction (CCUP)
 * @see CardDto
 * @see com.cardemo.service.card.CardDetailService
 * @see com.cardemo.service.card.CardListService
 * @see com.cardemo.service.card.CardUpdateService
 */
public interface CardService {

    // ---------------------------------------------------------------------
    // Card Detail operations (from CardDetailService / COCRDSLC.cbl)
    // ---------------------------------------------------------------------

    /**
     * Retrieves a single card's detail view by card number.
     *
     * <p>Performs a keyed read on the CARDDAT repository and returns the
     * populated {@link CardDto} containing card attributes (card number,
     * associated account ID, CVV, embossed name, expiration date, active
     * status) plus an optimistic-locking version token. Maps COBOL paragraph
     * {@code 9100-GETCARD-BYACCTCARD} (COCRDSLC.cbl lines 736-777).</p>
     *
     * <p>Input Validation (maps COBOL paragraph {@code 2220-EDIT-CARD}):
     * the card number must not be {@code null} or blank, must be numeric,
     * and must not exceed 16 characters.</p>
     *
     * <p>FILE STATUS mapping:</p>
     * <ul>
     *   <li>{@code 00 (NORMAL)} — returns the populated DTO</li>
     *   <li>{@code 23 (NOTFND)} — throws
     *       {@link com.cardemo.exception.RecordNotFoundException}
     *       (maps COBOL {@code DFHRESP(NOTFND)})</li>
     * </ul>
     *
     * <p>Implementation: {@code CardDetailService#getCardDetail(String)} runs
     * under class-level {@code @Transactional(readOnly = true)}.</p>
     *
     * @param cardNum the 16-digit numeric card number; must be non-blank,
     *                numeric, and not exceed 16 characters
     * @return the populated {@link CardDto} containing card attributes and
     *         the associated account ID
     * @throws IllegalArgumentException if {@code cardNum} is {@code null},
     *         blank, non-numeric, or exceeds 16 characters
     * @throws com.cardemo.exception.RecordNotFoundException if no card
     *         record exists for the given card number (maps FILE STATUS 23
     *         / {@code DFHRESP(NOTFND)})
     * @see <a href="file:app/cbl/COCRDSLC.cbl">COCRDSLC.cbl</a>
     *      — {@code PROCESS-ENTER-KEY} / {@code 9100-GETCARD-BYACCTCARD}
     *      paragraph
     */
    CardDto getCardDetail(String cardNum);

    /**
     * Retrieves all cards associated with the specified account ID.
     *
     * <p>Performs a non-unique alternate-index read on the CARDDAT repository
     * via the {@code CARDAIX} index (KEYS 11 16, NONUNIQUEKEY) and returns
     * all matching card records as a {@link List}. Maps COBOL paragraph
     * {@code 9150-GETCARD-BYACCT} (COCRDSLC.cbl lines 779-812), which uses
     * the {@code LIT-CARDFILENAME-ACCT-PATH 'CARDAIX '} alternate index to
     * browse all cards for a given account in ascending card-number order.</p>
     *
     * <p>The list is typically small (1–5 cards per account in production
     * data), so no pagination is required. The result is non-{@code null}
     * and may be empty only if the account exists but has no cards; if the
     * account cannot be located the implementation throws
     * {@link com.cardemo.exception.RecordNotFoundException}.</p>
     *
     * <p>Implementation: {@code CardDetailService#getCardsByAccountId(String)}
     * runs under class-level {@code @Transactional(readOnly = true)}.</p>
     *
     * @param acctId the 11-digit numeric account identifier; must be
     *               non-blank, numeric, and not exceed 11 characters
     * @return a non-{@code null} {@link List} of {@link CardDto} (possibly
     *         empty) containing all cards associated with the account
     * @throws IllegalArgumentException if {@code acctId} is {@code null},
     *         blank, non-numeric, or exceeds 11 characters
     * @throws com.cardemo.exception.RecordNotFoundException if no account
     *         matches the given ID (maps FILE STATUS 23 /
     *         {@code DFHRESP(NOTFND)})
     * @see <a href="file:app/cbl/COCRDSLC.cbl">COCRDSLC.cbl</a>
     *      — {@code 9150-GETCARD-BYACCT} paragraph
     */
    List<CardDto> getCardsByAccountId(String acctId);

    // ---------------------------------------------------------------------
    // Card List operations (from CardListService / COCRDLIC.cbl)
    // ---------------------------------------------------------------------

    /**
     * Lists cards with pagination, optionally filtered by account ID and/or
     * card number.
     *
     * <p>Performs a paginated browse of the CARDDAT repository with optional
     * exact-match filters on account ID and/or card number. Admin view (no
     * filters) returns all cards sorted by card number ascending using
     * native JPA pagination. Filtered view narrows the result set using AND
     * logic across the supplied filters. Maps COBOL paragraphs
     * {@code 9000-READ-FORWARD}, {@code 9500-FILTER-RECORDS},
     * {@code 2210-EDIT-ACCOUNT}, and {@code 2220-EDIT-CARD}
     * (COCRDLIC.cbl).</p>
     *
     * <p>Page size is fixed at 7 rows per page, matching the COBOL
     * constant {@code WS-MAX-SCREEN-LINES} from COCRDLIC.cbl, which
     * corresponds to the original BMS screen-map row capacity. This size
     * is defined in the implementation (not in this interface) and will
     * be centralized under {@code PaginationConstants} per AAP
     * Section 0.5.1.</p>
     *
     * <p>Implementation: {@code CardListService#listCards(int, String, String)}
     * runs under class-level {@code @Transactional(readOnly = true)}.</p>
     *
     * @param page          zero-based page number; must be {@code >= 0}
     * @param acctIdFilter  optional 11-digit numeric account ID filter;
     *                      {@code null} or blank means no account filter
     * @param cardNumFilter optional 16-digit numeric card number filter;
     *                      {@code null} or blank means no card-number
     *                      filter
     * @return a non-{@code null} {@link Page} of {@link CardDto} entries
     *         (possibly empty) with page size 7
     * @throws IllegalArgumentException if {@code page} is negative, or if
     *         {@code acctIdFilter} / {@code cardNumFilter} is
     *         structurally malformed (non-numeric or exceeds its
     *         permitted length)
     * @see <a href="file:app/cbl/COCRDLIC.cbl">COCRDLIC.cbl</a>
     *      — {@code PROCESS-PAGE-FORWARD} / {@code PROCESS-PAGE-BACKWARD}
     *      paragraphs
     */
    Page<CardDto> listCards(int page, String acctIdFilter, String cardNumFilter);

    /**
     * Lists cards for a specific account with pagination.
     *
     * <p>Performs a paginated browse of the CARDDAT repository scoped to a
     * single account, using the {@code CXACAIX} alternate-index pattern
     * from COCRDLIC.cbl (user view when {@code CDEMO-USER-TYPE = 'U'}).
     * Returns an empty {@link Page} when the account exists but has no
     * cards; throws
     * {@link com.cardemo.exception.RecordNotFoundException} when the
     * cross-reference records exist but no underlying card records are
     * found (data integrity violation).</p>
     *
     * <p>Page size is fixed at 7 rows per page, matching the COBOL
     * constant {@code WS-MAX-SCREEN-LINES}.</p>
     *
     * <p>Implementation:
     * {@code CardListService#listCardsByAccount(String, int)} runs under
     * class-level {@code @Transactional(readOnly = true)}.</p>
     *
     * @param acctId the 11-digit numeric account identifier; must be
     *               non-blank, numeric, and not exceed 11 characters
     * @param page   zero-based page number; must be {@code >= 0}
     * @return a non-{@code null} {@link Page} of {@link CardDto} entries
     *         (possibly empty) for the account with page size 7
     * @throws IllegalArgumentException if {@code acctId} is {@code null},
     *         blank, non-numeric, or exceeds 11 characters, or if
     *         {@code page} is negative
     * @throws com.cardemo.exception.RecordNotFoundException if
     *         cross-reference records for the account exist but no card
     *         records are found (data integrity violation — maps FILE
     *         STATUS 23 / {@code DFHRESP(NOTFND)})
     * @see <a href="file:app/cbl/COCRDLIC.cbl">COCRDLIC.cbl</a>
     *      — account-scoped browse via {@code CXACAIX} alternate index
     */
    Page<CardDto> listCardsByAccount(String acctId, int page);

    // ---------------------------------------------------------------------
    // Card Update operations (from CardUpdateService / COCRDUPC.cbl)
    // ---------------------------------------------------------------------

    /**
     * Retrieves a card for the update view, populating the update DTO with
     * the current card state and the optimistic-locking version token.
     *
     * <p>Performs a read-only transactional lookup of the card by its
     * primary key (card number) and returns the populated {@link CardDto}
     * including the JPA {@code @Version} field. The caller is expected to
     * mutate the returned DTO and submit it to
     * {@link #updateCard(String, CardDto)}; the version token enables
     * optimistic concurrency control on the subsequent write. Maps COBOL
     * paragraphs {@code 9000-READ-DATA} / {@code 9100-GETCARD-BYACCTCARD}
     * (COCRDUPC.cbl), which correspond to {@code EXEC CICS READ
     * FILE(CARDFILENAME) RIDFLD(WS-CARD-RID-CARDNUM)}.</p>
     *
     * <p>FILE STATUS mapping:</p>
     * <ul>
     *   <li>{@code 00 (NORMAL)} — returns the populated DTO with current
     *       version token</li>
     *   <li>{@code 23 (NOTFND)} — throws
     *       {@link com.cardemo.exception.RecordNotFoundException}</li>
     * </ul>
     *
     * <p>Implementation:
     * {@code CardUpdateService#getCardForUpdate(String)} is annotated
     * {@code @Transactional(readOnly = true)}.</p>
     *
     * @param cardNum the 16-digit numeric card number (primary key) of
     *                the card to retrieve
     * @return the populated {@link CardDto} containing current card
     *         attributes and the optimistic-locking version token
     * @throws com.cardemo.exception.RecordNotFoundException if no card
     *         exists with the given number (maps FILE STATUS 23 /
     *         {@code DFHRESP(NOTFND)})
     * @see <a href="file:app/cbl/COCRDUPC.cbl">COCRDUPC.cbl</a>
     *      — {@code READ-CARDDAT-FILE} / {@code 9100-GETCARD-BYACCTCARD}
     *      paragraph (initial read before update)
     */
    CardDto getCardForUpdate(String cardNum);

    /**
     * Applies updates to the specified card with optimistic locking.
     *
     * <p>Validates the submitted {@link CardDto} (card number length, CVV
     * format, embossed-name pattern, expiration month/year range, active
     * status flag), verifies the associated account exists, detects
     * whether any field actually changed, and — if so — persists the
     * updated card under JPA {@code @Version} optimistic locking.
     * Key fields {@code cardNum} and {@code cardAcctId} are NEVER
     * modified, matching the COBOL key-field immutability rule.</p>
     *
     * <p>Maps COBOL paragraphs {@code 1100-VALIDATE-CARD-DATA} (field
     * validation, paragraphs 1210–1260), {@code 1200-CHECK-FOR-CHANGES}
     * (upper-case comparison), {@code 9200-WRITE-PROCESSING} (save
     * step), and {@code 9300-CHECK-CHANGE-IN-REC} (optimistic locking
     * conflict detection) from COCRDUPC.cbl.</p>
     *
     * <p>The implementation collects ALL field-validation errors before
     * throwing {@link com.cardemo.exception.ValidationException},
     * matching the COBOL behavior of marking all invalid fields rather
     * than short-circuiting on the first error.</p>
     *
     * <p>Optimistic-locking conflict detection compares the
     * client-supplied version on the submitted DTO to the current DB
     * version. A mismatch — either pre-write (explicit version check)
     * or at commit time (JPA {@code OptimisticLockException}) — throws
     * {@link com.cardemo.exception.ConcurrentModificationException},
     * mapped to HTTP 409 Conflict in the REST layer.</p>
     *
     * <p>Implementation:
     * {@code CardUpdateService#updateCard(String, CardDto)} is annotated
     * {@code @Transactional} (read-write).</p>
     *
     * @param cardNum       the 16-digit numeric card number identifying
     *                      the card to update; must not be {@code null}
     * @param updateRequest the {@link CardDto} containing new field
     *                      values plus the version token obtained from
     *                      a prior call to
     *                      {@link #getCardForUpdate(String)}; must not
     *                      be {@code null}
     * @return the updated {@link CardDto} with the refreshed version
     *         token reflecting the post-save state
     * @throws com.cardemo.exception.RecordNotFoundException if no card
     *         exists with the given number, or if the associated
     *         account cannot be located (maps FILE STATUS 23 /
     *         {@code DFHRESP(NOTFND)})
     * @throws com.cardemo.exception.ValidationException if one or more
     *         field-level validations fail (collected errors per COBOL
     *         {@code 1100-VALIDATE-CARD-DATA} behavior)
     * @throws com.cardemo.exception.ConcurrentModificationException if
     *         the version token has changed since the pre-fetch (maps
     *         COBOL {@code 9300-CHECK-CHANGE-IN-REC} "record modified
     *         by another user" semantics)
     * @see <a href="file:app/cbl/COCRDUPC.cbl">COCRDUPC.cbl</a>
     *      — {@code UPDATE-CARDDAT-FILE} / {@code 9200-WRITE-PROCESSING}
     *      paragraph
     */
    CardDto updateCard(String cardNum, CardDto updateRequest);
}
