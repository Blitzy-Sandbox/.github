package com.cardemo.domain.constants;

/**
 * Centralized constant holder for pagination page-size values used across the
 * CardDemo list service classes.
 *
 * <p>Consolidates {@code PAGE_SIZE} declarations that were previously
 * duplicated in {@link com.cardemo.service.admin.UserListService},
 * {@link com.cardemo.service.card.CardListService}, and
 * {@link com.cardemo.service.transaction.TransactionListService}. This class
 * follows the Constant Holder Pattern described in AAP &sect;0.4.3 &mdash; a
 * {@code public final} class with a {@code private} no-argument constructor to
 * prevent instantiation and subclassing.</p>
 *
 * <h3>COBOL Source Traceability</h3>
 * <p>Each page-size value corresponds to a legacy CICS BMS screen row capacity
 * or a COBOL {@code WORKING-STORAGE} table dimension from the original
 * mainframe programs {@code COUSR00C.cbl}, {@code COCRDLIC.cbl}, and
 * {@code COTRN00C.cbl}. Maintaining these exact values is required by AAP
 * Rule R-001 to preserve 100% behavioral parity with the legacy screens
 * &mdash; changing any value would break the COMMAREA page navigation
 * contract and alter user-visible pagination behavior.</p>
 *
 * <h3>Consuming Classes (post-refactoring)</h3>
 * <ul>
 *   <li>{@link com.cardemo.service.admin.UserListService} &rarr;
 *       {@link #USER_PAGE_SIZE}</li>
 *   <li>{@link com.cardemo.service.card.CardListService} &rarr;
 *       {@link #CARD_PAGE_SIZE}</li>
 *   <li>{@link com.cardemo.service.transaction.TransactionListService} &rarr;
 *       {@link #TRANSACTION_PAGE_SIZE}</li>
 * </ul>
 *
 * <h3>Thread Safety</h3>
 * <p>All fields are primitive {@code int} literals and are therefore
 * immutable and safe to share across threads as {@code public static final}
 * singletons.</p>
 *
 * @see com.cardemo.service.admin.UserListService
 * @see com.cardemo.service.card.CardListService
 * @see com.cardemo.service.transaction.TransactionListService
 */
public final class PaginationConstants {

    /**
     * Number of user records returned per page &mdash; matches the COBOL
     * {@code WS-USER-DATA} structure which defines
     * {@code USER-REC OCCURS 10 TIMES} in {@code COUSR00C.cbl} (line 57).
     *
     * <p>The original CICS online program reads exactly 10 records per
     * {@code READNEXT} loop iteration before performing one additional
     * {@code READNEXT} to check for the existence of a next page. Changing
     * this value would break 100% behavioral parity with the original COBOL
     * program per AAP Rule R-001.</p>
     */
    public static final int USER_PAGE_SIZE = 10;

    /**
     * Number of cards displayed per page &mdash; matches the COBOL
     * {@code WS-MAX-SCREEN-LINES = 7} constant from {@code COCRDLIC.cbl}
     * (line ~178).
     *
     * <p>The original CICS BMS screen for the card list reserves exactly 7
     * rows for card records. Changing this value would alter the pagination
     * behavior relative to the legacy screen and break 100% behavioral
     * parity per AAP Rule R-001.</p>
     */
    public static final int CARD_PAGE_SIZE = 7;

    /**
     * Number of transactions displayed per page &mdash; matches the COBOL
     * 10-record browse loop in {@code COTRN00C.cbl} (lines 290, 297):
     * <pre>
     * PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL WS-IDX &gt; 10
     * ...
     * PERFORM UNTIL WS-IDX &gt;= 11 OR TRANSACT-EOF OR ERR-FLG-ON
     * </pre>
     *
     * <p>This constant MUST remain 10 to maintain 100% behavioral parity
     * with the original COBOL program per AAP Rule R-001. Changing this
     * value would break the COMMAREA page navigation contract.</p>
     */
    public static final int TRANSACTION_PAGE_SIZE = 10;

    /**
     * Private constructor prevents instantiation of this constant holder
     * class.
     *
     * @throws AssertionError always, to defend against reflection-based
     *                       instantiation attempts.
     */
    private PaginationConstants() {
        throw new AssertionError(
                "PaginationConstants is a constant holder and must not be instantiated.");
    }
}
