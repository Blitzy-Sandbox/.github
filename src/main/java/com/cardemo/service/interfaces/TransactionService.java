/*
 * TransactionService.java — Transaction Domain Service Interface
 *
 * Public service contract for transaction-domain operations in the CardDemo
 * application. Aggregates the public methods of the three concrete
 * {@code @Service} classes under {@code com.cardemo.service.transaction}:
 *   - TransactionAddService    (COTRN02C.cbl, CICS transaction CT02 — add)
 *   - TransactionDetailService (COTRN01C.cbl, CICS transaction CT01 — view)
 *   - TransactionListService   (COTRN00C.cbl, CICS transaction CT00 — list)
 *
 * This file implements AAP Section 0.5.1 "New Service Interface Files
 * (CREATE)" and follows the design rules in the folder-level AAP for
 * {@code service/interfaces/}. No business logic is expressed here —
 * this is a pure Java contract (no default methods, no annotations on
 * the type). Exceptions thrown by implementations are Java unchecked
 * exceptions and therefore do NOT appear in {@code throws} clauses,
 * only in Javadoc {@code @throws} tags for documentation.
 *
 * The nested record type {@code TransactionListService.PageNavigation}
 * remains defined on its concrete service class per the folder-level AAP
 * minimal-change rule; this interface references it by qualified name.
 *
 * AAP Rule Compliance:
 *   R-001 — No business logic; interface-only declarations
 *   R-004 — COBOL {@see} references preserved for traceability
 *   R-006 — Method signatures match concrete sources exactly
 *           (including TYPED generic {@code Page<TransactionDto>} on
 *            {@link #hasNextPage(Page)} and
 *            {@link #getPageNavigation(Page)} —
 *            NOT the wildcard {@code Page<?>} used in
 *            {@link com.cardemo.service.interfaces.AdminService})
 *
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 * Licensed under the Apache License, Version 2.0
 */
package com.cardemo.service.interfaces;

import com.cardemo.model.dto.TransactionDto;
import com.cardemo.service.transaction.TransactionListService;
import org.springframework.data.domain.Page;

/**
 * Service contract for transaction-domain operations in the CardDemo application.
 *
 * <p>This interface defines the public service boundary for the transaction
 * domain. Implementations are provided by Spring-managed {@code @Service}
 * classes in {@code com.cardemo.service.transaction}. Consumers (e.g.,
 * {@code TransactionController}) depend on this interface rather than on
 * concrete classes so that alternative implementations may be supplied for
 * testing (mocks) or future extension without modifying the consumer
 * code.</p>
 *
 * <p>The interface aggregates add (write), detail lookup (read), and list
 * (read) operations across three concrete service classes, each of which
 * migrates one COBOL online program:</p>
 * <ul>
 *   <li>{@code TransactionAddService}    — create a transaction with
 *       auto-generated 16-digit ID (COTRN02C.cbl, CT02)</li>
 *   <li>{@code TransactionDetailService} — read a single transaction by ID
 *       (COTRN01C.cbl, CT01)</li>
 *   <li>{@code TransactionListService}   — paginated browse with optional
 *       starting-ID filter (COTRN00C.cbl, CT00)</li>
 * </ul>
 *
 * <p>The six methods declared here correspond exactly to the public methods
 * of these three classes. No single concrete class implements all six
 * methods; each concrete implementation is responsible for its own
 * domain-specific subset. See the folder-level AAP note for the
 * implementation strategy.</p>
 *
 * <h3>Nested Record Type (Minimal-Change Rule)</h3>
 * <p>Per the folder-level AAP minimal-change rule, the public nested record
 * type used as the return type of {@link #getPageNavigation(Page)} remains
 * defined on its concrete service class rather than being extracted to a
 * separate file:</p>
 * <ul>
 *   <li>{@link com.cardemo.service.transaction.TransactionListService.PageNavigation}
 *       — four fields: {@code firstTransactionId}, {@code lastTransactionId},
 *       {@code pageNumber}, {@code hasNextPage}. Maps COBOL COMMAREA
 *       fields {@code CDEMO-CT00-TRNID-FIRST}, {@code CDEMO-CT00-TRNID-LAST},
 *       {@code CDEMO-CT00-PAGE-NUM}, and {@code CDEMO-CT00-NEXT-PAGE-FLG}
 *       from COTRN00C.cbl (lines 62-68).</li>
 * </ul>
 *
 * <p>The import of {@link com.cardemo.service.transaction.TransactionListService}
 * in this compilation unit is strictly to resolve the nested-type reference;
 * no logic is invoked from the concrete class through this interface.</p>
 *
 * <h3>Transactional Semantics (Implementation Concern)</h3>
 * <p>Transactional behavior is a property of the implementing classes, not
 * of this interface. As documented in the concrete sources:</p>
 * <ul>
 *   <li>{@code TransactionAddService#addTransaction(TransactionDto)} carries
 *       method-level {@code @Transactional} — writes a new transaction
 *       atomically.</li>
 *   <li>{@code TransactionAddService#copyFromTransaction(String)} carries
 *       method-level {@code @Transactional(readOnly = true)} — reads a
 *       template transaction for copy-forward pre-population.</li>
 *   <li>{@code TransactionDetailService#getTransaction(String)} carries
 *       method-level {@code @Transactional(readOnly = true)} — single
 *       keyed read of the TRANSACT dataset.</li>
 *   <li>{@code TransactionListService#listTransactions(String, int)} carries
 *       method-level {@code @Transactional(readOnly = true)} — paginated
 *       browse of the TRANSACT dataset.</li>
 *   <li>{@code TransactionListService#hasNextPage(Page)} and
 *       {@code TransactionListService#getPageNavigation(Page)} are pure
 *       helpers that operate on an already-materialized {@link Page} and
 *       do not carry transactional annotations.</li>
 * </ul>
 *
 * <h3>Precision Rule</h3>
 * <p>All monetary amounts in {@link TransactionDto} (notably
 * {@code tranAmt}) use {@link java.math.BigDecimal} — zero
 * {@code float}/{@code double} substitution per AAP §0.8.2, preserving
 * COBOL {@code PIC S9(09)V99 COMP-3} packed decimal precision.</p>
 *
 * <h3>COBOL Source References</h3>
 * <p>These references provide traceability back to the original mainframe
 * programs (see {@code TRACEABILITY_MATRIX.md} for the full paragraph-level
 * mapping):</p>
 *
 * @see <a href="file:app/cbl/COTRN00C.cbl">COTRN00C.cbl</a>
 *      — Transaction List online transaction (CT00)
 * @see <a href="file:app/cbl/COTRN01C.cbl">COTRN01C.cbl</a>
 *      — Transaction View/Detail online transaction (CT01)
 * @see <a href="file:app/cbl/COTRN02C.cbl">COTRN02C.cbl</a>
 *      — Transaction Add online transaction (CT02)
 * @see TransactionDto
 * @see com.cardemo.service.transaction.TransactionAddService
 * @see com.cardemo.service.transaction.TransactionDetailService
 * @see com.cardemo.service.transaction.TransactionListService
 */
public interface TransactionService {

    // ---------------------------------------------------------------------
    // Transaction Add operations (from TransactionAddService / COTRN02C.cbl)
    // ---------------------------------------------------------------------

    /**
     * Creates a new transaction record with validation and cross-reference
     * to the associated account and card.
     *
     * <p>Validates the submitted {@link TransactionDto} (amount scale and
     * range, card/account cross-reference existence, transaction type and
     * category code lookups, origin- and process-timestamp date validation),
     * generates a unique 16-digit sequential transaction ID (COBOL PIC 9(16)
     * zero-padded), persists the transaction to the TRANSACT repository, and
     * returns the persisted DTO with the generated ID populated.</p>
     *
     * <p>Maps COBOL paragraphs {@code MAIN-PARA}, {@code PROCESS-ENTER-KEY},
     * {@code VALIDATE-INPUT-KEY-FIELDS}, {@code VALIDATE-INPUT-DATA-FIELDS},
     * {@code GENERATE-NEXT-TRAN-ID}, and {@code WRITE-TRANSACT-FILE}
     * (COTRN02C.cbl lines 140-587).</p>
     *
     * <p>Implementation: {@code TransactionAddService#addTransaction(TransactionDto)}
     * annotated {@code @Transactional}.</p>
     *
     * @param request the {@link TransactionDto} containing transaction
     *                details — card number, account ID (one of which must
     *                resolve via cross-reference), type/category codes,
     *                amount (BigDecimal, PIC S9(09)V99 range), description,
     *                merchant fields, and origin/processing timestamps;
     *                must not be {@code null}
     * @return the persisted {@link TransactionDto} with the auto-generated
     *         16-character transaction ID populated
     * @throws com.cardemo.exception.ValidationException      if any input
     *         field fails validation (maps COBOL "…invalid…" error
     *         messages from VALIDATE-INPUT-* paragraphs)
     * @throws com.cardemo.exception.RecordNotFoundException if the card
     *         number or account ID cannot be resolved via cross-reference
     *         lookup (maps COBOL DFHRESP(NOTFND) on CARDXREF / CXACAIX)
     * @throws com.cardemo.exception.DuplicateRecordException if the
     *         generated transaction ID unexpectedly collides with an
     *         existing record (maps COBOL DFHRESP(DUPKEY) / DFHRESP(DUPREC)
     *         — FILE STATUS 22)
     * @throws com.cardemo.exception.CardDemoException       for unexpected
     *         write failures (maps COBOL "Unable to Write Transaction..."
     *         OTHER response)
     * @see <a href="file:app/cbl/COTRN02C.cbl">COTRN02C.cbl</a>
     *      — WRITE-TRANSACT-FILE paragraph
     */
    TransactionDto addTransaction(TransactionDto request);

    /**
     * Retrieves an existing transaction for use as a template when adding a
     * new transaction (COBOL "copy from" feature).
     *
     * <p>Maps COBOL paragraph {@code COPY-LAST-TRAN-DATA} (COTRN02C.cbl
     * lines 595-650, invoked via PF5). The COBOL program copies the data
     * fields from a previously viewed/entered transaction to pre-populate
     * the add-transaction BMS screen for operator convenience. In the REST
     * migration, this method reads the specified transaction and returns a
     * template DTO with all data fields populated <strong>except</strong>
     * the transaction ID, which is cleared so that a fresh ID is
     * auto-generated when the template is submitted via
     * {@link #addTransaction(TransactionDto)}.</p>
     *
     * <p>Implementation: {@code TransactionAddService#copyFromTransaction(String)}
     * annotated {@code @Transactional(readOnly = true)}.</p>
     *
     * @param sourceTransactionId the 16-character transaction identifier to
     *                            copy from; must correspond to an existing
     *                            transaction in the TRANSACT repository
     * @return a {@link TransactionDto} populated from the source transaction
     *         with the transaction ID field cleared (set to {@code null})
     *         ready for submission as a new transaction
     * @throws com.cardemo.exception.RecordNotFoundException if no
     *         transaction exists with the given source ID (maps COBOL
     *         DFHRESP(NOTFND) on TRANSACT READ)
     * @see <a href="file:app/cbl/COTRN02C.cbl">COTRN02C.cbl</a>
     *      — READ-TRANSACT-FILE paragraph (invoked when the operator
     *      specifies a source transaction to copy via PF5)
     */
    TransactionDto copyFromTransaction(String sourceTransactionId);

    // ---------------------------------------------------------------------
    // Transaction Detail operations (from TransactionDetailService / COTRN01C.cbl)
    // ---------------------------------------------------------------------

    /**
     * Retrieves a single transaction by its transaction ID for detail display.
     *
     * <p>Performs a keyed read on the TRANSACT repository and returns the
     * populated {@link TransactionDto} with all 13 data fields from the
     * TRAN-RECORD layout (CVTRA05Y.cpy, 350 bytes). Maps COBOL paragraphs
     * {@code MAIN-PARA}, {@code PROCESS-ENTER-KEY}, and
     * {@code READ-TRANSACT-FILE} (COTRN01C.cbl lines 86-296).</p>
     *
     * <p>FILE STATUS mapping:</p>
     * <ul>
     *   <li>{@code 00 (NORMAL)} — returns the populated DTO</li>
     *   <li>{@code 23 (NOTFND)} — throws
     *       {@link com.cardemo.exception.RecordNotFoundException}</li>
     * </ul>
     *
     * <p>Implementation: {@code TransactionDetailService#getTransaction(String)}
     * annotated {@code @Transactional(readOnly = true)}.</p>
     *
     * @param transactionId the 16-character transaction identifier; must be
     *                      non-{@code null} and non-blank (maps COBOL
     *                      {@code TRNIDINI} input field, PIC X(16))
     * @return the populated {@link TransactionDto} with all transaction
     *         detail fields mapped from the TRAN-RECORD layout
     * @throws IllegalArgumentException if {@code transactionId} is
     *         {@code null}, blank, or not 16 characters in length (maps
     *         COBOL input validation "Tran ID can NOT be empty..." at
     *         COTRN01C.cbl line 147)
     * @throws com.cardemo.exception.RecordNotFoundException if no
     *         transaction matches the given ID (maps COBOL
     *         DFHRESP(NOTFND) at COTRN01C.cbl line 283: "Transaction ID
     *         NOT found...")
     * @see <a href="file:app/cbl/COTRN01C.cbl">COTRN01C.cbl</a>
     *      — READ-TRANSACT-FILE paragraph
     */
    TransactionDto getTransaction(String transactionId);

    // ---------------------------------------------------------------------
    // Transaction List operations (from TransactionListService / COTRN00C.cbl)
    // ---------------------------------------------------------------------

    /**
     * Lists transactions with pagination, optionally starting from a
     * specified transaction ID (COBOL STARTBR/READNEXT browse).
     *
     * <p>Executes a paginated query with page size fixed at 10 rows and
     * ascending sort on {@code tranId}, preserving the COBOL VSAM KSDS
     * ascending key sequence for {@code READNEXT} browse. The query
     * behavior depends on the {@code startTransactionId} argument:</p>
     * <ul>
     *   <li>When {@code startTransactionId} is non-{@code null} and
     *       non-blank, the query filters to transactions whose ID is
     *       greater than or equal to the supplied value (maps COBOL
     *       {@code STARTBR TRANSACT RIDFLD(TRAN-ID)} with a specific
     *       starting key — COTRN00C.cbl line 210).</li>
     *   <li>When {@code startTransactionId} is {@code null} or blank, the
     *       query browses from the beginning (maps COBOL {@code STARTBR}
     *       with {@code LOW-VALUES} — COTRN00C.cbl line 207).</li>
     * </ul>
     *
     * <p>The returned {@link Page} carries pagination metadata including
     * total element count, {@code hasNext}, current page number, and
     * actual elements on this page — matching the semantics of COBOL
     * COMMAREA navigation fields (CDEMO-CT00-*). Page size is fixed at 10
     * to preserve behavioral parity with {@code COTRN00C.cbl} lines
     * 290/297: {@code PERFORM UNTIL WS-IDX >= 11}.</p>
     *
     * <p>Implementation: {@code TransactionListService#listTransactions(String, int)}
     * annotated {@code @Transactional(readOnly = true)}.</p>
     *
     * @param startTransactionId optional starting transaction ID (inclusive)
     *                           for range filtering; {@code null} or blank
     *                           to list from the first record (maps COBOL
     *                           {@code TRNIDINI} input field)
     * @param page               zero-based page number (maps COBOL
     *                           {@code CDEMO-CT00-PAGE-NUM}); implementation
     *                           clamps negative values to 0
     * @return a {@link Page} of {@link TransactionDto} entries (page size
     *         10) with pagination metadata; never {@code null}
     * @see <a href="file:app/cbl/COTRN00C.cbl">COTRN00C.cbl</a>
     *      — PROCESS-PAGE-FORWARD paragraph (lines 279-328)
     */
    Page<TransactionDto> listTransactions(String startTransactionId, int page);

    /**
     * Determines whether additional transaction pages exist beyond the
     * given page.
     *
     * <p>Delegates to {@link Page#hasNext()} on the supplied page, which
     * compares the total element count against the current page position
     * and page size. Maps COBOL {@code CDEMO-CT00-NEXT-PAGE-FLG} logic
     * (COTRN00C.cbl lines 305-320) where the COBOL program performs an
     * additional {@code READNEXT} after 10 records to determine whether
     * more records exist.</p>
     *
     * <p>Implementation: {@code TransactionListService#hasNextPage(Page)}
     * — pure helper with no transactional annotation.</p>
     *
     * <p><strong>Signature note:</strong> This method uses TYPED generic
     * {@code Page<TransactionDto>} (matching the concrete source),
     * <em>not</em> the wildcard {@code Page<?>} used in
     * {@link com.cardemo.service.interfaces.AdminService#hasNextPage(Page)}.
     * The stricter generic is preserved exactly per AAP Rule R-006.</p>
     *
     * @param currentPage the current {@link Page} of {@link TransactionDto}
     *                    whose {@code hasNext} flag is to be evaluated;
     *                    must not be {@code null}
     * @return {@code true} if at least one more page exists beyond
     *         {@code currentPage}; {@code false} if {@code currentPage} is
     *         the last page (maps COBOL {@code CDEMO-CT00-NEXT-PAGE-FLG}
     *         = {@code 'Y'} vs {@code 'N'})
     * @see <a href="file:app/cbl/COTRN00C.cbl">COTRN00C.cbl</a>
     *      — evaluation of next-page flag (lines 305-320)
     */
    boolean hasNextPage(Page<TransactionDto> currentPage);

    /**
     * Builds a page-navigation summary (first ID, last ID, page number,
     * has-next flag) from a loaded page.
     *
     * <p>Constructs an immutable
     * {@link com.cardemo.service.transaction.TransactionListService.PageNavigation}
     * record capturing the first and last transaction IDs present on the
     * current page, the zero-based page number, and the next-page
     * indicator. For empty pages, both first and last transaction IDs are
     * {@code null}.</p>
     *
     * <p>Maps COBOL COMMAREA navigation fields from {@code COTRN00C.cbl}:</p>
     * <ul>
     *   <li>{@code CDEMO-CT00-TRNID-FIRST PIC X(16)} (line 63) — set in
     *       {@code POPULATE-TRAN-DATA} when {@code WS-IDX = 1}
     *       (line 393)</li>
     *   <li>{@code CDEMO-CT00-TRNID-LAST PIC X(16)} (line 64) — set in
     *       {@code POPULATE-TRAN-DATA} when {@code WS-IDX = 10}
     *       (line 439)</li>
     *   <li>{@code CDEMO-CT00-PAGE-NUM PIC 9(08)} (line 65) — current
     *       page number</li>
     *   <li>{@code CDEMO-CT00-NEXT-PAGE-FLG PIC X(01)} (line 66) —
     *       {@code 'Y'} if more pages exist</li>
     * </ul>
     *
     * <p>Implementation: {@code TransactionListService#getPageNavigation(Page)}
     * — pure helper with no transactional annotation.</p>
     *
     * <p><strong>Signature note:</strong> This method uses TYPED generic
     * {@code Page<TransactionDto>} (matching the concrete source) and
     * returns the qualified nested record type
     * {@link TransactionListService.PageNavigation}. The nested record
     * remains defined on its concrete service class per the folder-level
     * AAP minimal-change rule.</p>
     *
     * @param page the loaded {@link Page} of {@link TransactionDto} from
     *             which to extract navigation metadata; must not be
     *             {@code null}
     * @return a {@link TransactionListService.PageNavigation} record
     *         containing the first ID, last ID, page number, and has-next
     *         flag for the supplied page
     * @see <a href="file:app/cbl/COTRN00C.cbl">COTRN00C.cbl</a>
     *      — CDEMO-CT00-* navigation fields (lines 62-68) and
     *      POPULATE-TRAN-DATA paragraph (lines 381-445)
     */
    TransactionListService.PageNavigation getPageNavigation(Page<TransactionDto> page);
}
