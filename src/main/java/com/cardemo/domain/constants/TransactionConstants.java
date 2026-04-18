package com.cardemo.domain.constants;

import java.math.BigDecimal;

/**
 * Centralized constant holder for transaction-related values used across the
 * transaction and billing service classes.
 *
 * <p>Consolidates {@code static final} field declarations that were previously
 * duplicated between {@link com.cardemo.service.transaction.TransactionAddService}
 * and {@link com.cardemo.service.billing.BillPaymentService}. This class follows
 * the Constant Holder Pattern described in AAP &sect;0.4.3 &mdash; a
 * {@code public final} class with a {@code private} no-argument constructor to
 * prevent instantiation and subclassing.</p>
 *
 * <h3>COBOL Source Traceability</h3>
 * <p>Transaction fixed-length identifiers and amount precision originate from
 * the COBOL copybook {@code CVTRA05Y.cpy} (transaction record layout) and the
 * programs {@code COTRN02C.cbl} (transaction add) and {@code COBIL00C.cbl}
 * (bill payment) per {@code TRACEABILITY_MATRIX.md}.</p>
 *
 * <h3>Consuming Classes (post-refactoring)</h3>
 * <ul>
 *   <li>{@code com.cardemo.service.transaction.TransactionAddService}</li>
 *   <li>{@code com.cardemo.service.billing.BillPaymentService}</li>
 *   <li>{@code com.cardemo.domain.validation.TransactionValidator}</li>
 * </ul>
 *
 * <h3>Thread Safety</h3>
 * <p>{@link BigDecimal} is immutable, so the amount boundary constants are safe
 * to expose as shared {@code public static final} singletons. All primitive and
 * {@link String} constants are likewise immutable.</p>
 *
 * @see com.cardemo.service.transaction.TransactionAddService
 * @see com.cardemo.service.billing.BillPaymentService
 */
public final class TransactionConstants {

    /**
     * Expected length of transaction IDs &mdash; matches COBOL PIC 9(16).
     *
     * <p>Transaction identifiers are always 16 digits, zero-padded on the left.
     * Used by transaction validation to enforce exact-length constraints.
     * Originates from the {@code TRAN-ID} field in COBOL copybook
     * {@code CVTRA05Y.cpy}.</p>
     */
    public static final int TRAN_ID_LENGTH = 16;

    /**
     * Format string used to zero-pad transaction IDs to 16 digits.
     *
     * <p>Used with {@link String#format(String, Object...)} to produce a
     * 16-digit zero-padded decimal string (e.g., {@code "0000000000000042"}).
     * Matches the COBOL PIC 9(16) field convention for the {@code TRAN-ID}
     * field of {@code CVTRA05Y.cpy}.</p>
     */
    public static final String TRAN_ID_FORMAT = "%016d";

    /**
     * Alias of {@link #TRAN_ID_FORMAT} preserved for
     * {@link com.cardemo.service.billing.BillPaymentService} call-site clarity.
     *
     * <p>AAP &sect;0.5.1 lists {@code TRANSACTION_ID_FORMAT} as a separate
     * consolidation target &mdash; both names resolve to the same literal
     * {@code "%016d"} and can be used interchangeably. Historically this form
     * of the constant lived in {@code BillPaymentService.java} while the
     * shorter {@code TRAN_ID_FORMAT} form lived in
     * {@code TransactionAddService.java}.</p>
     */
    public static final String TRANSACTION_ID_FORMAT = "%016d";

    /**
     * First transaction ID used when the transactions table is empty.
     *
     * <p>When no transactions exist yet, new transaction ID generation starts
     * from this 16-digit zero-padded value ({@code "0000000000000001"}),
     * matching the COBOL behavior of the {@code COTRN02C.cbl} high-key seek
     * plus increment paragraph (first-ever transaction handling for the
     * {@code NOTFND} branch &mdash; {@code MOVE ZEROS + ADD 1}).</p>
     */
    public static final String FIRST_TRANSACTION_ID = "0000000000000001";

    /**
     * Default starting transaction ID used when the transactions table is
     * empty &mdash; numeric counterpart to {@link #FIRST_TRANSACTION_ID}.
     *
     * <p>Used by {@link com.cardemo.service.billing.BillPaymentService} as the
     * seed value before applying {@link #TRANSACTION_ID_FORMAT} for string
     * formatting. Maps the COBOL sequence in {@code COBIL00C.cbl} lines
     * 487-488 and 217 where {@code READPREV} returning {@code ENDFILE}
     * triggers {@code MOVE ZEROS TO TRAN-ID} followed by
     * {@code ADD 1 TO WS-TRAN-ID-NUM}.</p>
     */
    public static final long DEFAULT_STARTING_ID = 1L;

    /**
     * Maximum transaction amount (COBOL PIC S9(09)V99).
     *
     * <p>Upper bound of the valid transaction amount range (inclusive).
     * Matches the COBOL signed packed-decimal representation with 9 integer
     * digits and 2 fractional digits &mdash; concrete value
     * {@code 999,999,999.99}. The {@link BigDecimal#BigDecimal(String)}
     * constructor is used (rather than the {@code double} constructor) to
     * preserve the exact decimal representation and scale of 2, matching the
     * COBOL V99 implicit decimal point convention.</p>
     */
    public static final BigDecimal MAX_TRANSACTION_AMOUNT = new BigDecimal("999999999.99");

    /**
     * Minimum transaction amount (COBOL PIC S9(09)V99 negative).
     *
     * <p>Lower bound of the valid transaction amount range (inclusive).
     * Matches the COBOL signed packed-decimal negative representation with 9
     * integer digits and 2 fractional digits &mdash; concrete value
     * {@code -999,999,999.99}. The {@link BigDecimal#BigDecimal(String)}
     * constructor is used to preserve exact decimal representation and
     * scale=2 semantics identical to the COBOL V99 field.</p>
     */
    public static final BigDecimal MIN_TRANSACTION_AMOUNT = new BigDecimal("-999999999.99");

    /**
     * Required scale for transaction amounts (COBOL V99 = 2 decimal positions).
     *
     * <p>All transaction amount {@link BigDecimal} values MUST have scale
     * exactly 2 to preserve monetary precision semantics equivalent to the
     * COBOL V99 implicit decimal point convention. Callers typically use this
     * constant with {@link BigDecimal#scale()} equality checks to reject
     * inputs with too many fractional digits.</p>
     */
    public static final int MAX_AMOUNT_SCALE = 2;

    /**
     * Private constructor prevents instantiation of this constant holder class.
     *
     * @throws AssertionError always, to defend against reflection-based
     *                       instantiation attempts.
     */
    private TransactionConstants() {
        throw new AssertionError(
                "TransactionConstants is a constant holder and must not be instantiated.");
    }
}
