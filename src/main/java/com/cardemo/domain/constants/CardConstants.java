package com.cardemo.domain.constants;

/**
 * Centralized constant holder for the card domain.
 *
 * <p>Consolidates {@code static final} field declarations that were previously
 * duplicated across {@link com.cardemo.service.card.CardDetailService},
 * {@link com.cardemo.service.card.CardListService}, and
 * {@link com.cardemo.service.card.CardUpdateService}. This class follows the
 * Constant Holder Pattern described in AAP &sect;0.4.3 &mdash; a
 * {@code public final} class with a {@code private} no-argument constructor to
 * prevent instantiation and subclassing.</p>
 *
 * <h3>COBOL Source Traceability</h3>
 * <p>Card field widths, expiry bounds, and masking rules originate from the
 * COBOL copybook {@code CVACT02Y.cpy} (card master record) and the online
 * programs {@code COCRDSLC.cbl} (detail), {@code COCRDLIC.cbl} (list), and
 * {@code COCRDUPC.cbl} (update) per {@code TRACEABILITY_MATRIX.md}. The
 * expiration-range bounds mirror the COBOL {@code 1250-EDIT-EXPIRY-MON} and
 * {@code 1260-EDIT-EXPIRY-YEAR} paragraphs of {@code COCRDUPC.cbl}, and the
 * account-ID / card-number widths mirror the {@code CARD-ACCT-ID PIC 9(11)}
 * and {@code CARD-NUM PIC X(16)} fields of {@code CVACT02Y.cpy}.</p>
 *
 * <h3>Consuming Classes (post-refactoring)</h3>
 * <ul>
 *   <li>{@link com.cardemo.service.card.CardDetailService} &rarr;
 *       {@link #CARD_NUM_MAX_LENGTH}, {@link #ACCT_ID_MAX_LENGTH}</li>
 *   <li>{@link com.cardemo.service.card.CardListService} &rarr;
 *       {@link #ACCT_ID_MAX_LENGTH}, {@link #CARD_NUM_MAX_LENGTH},
 *       {@link #MASK_VISIBLE_DIGITS}</li>
 *   <li>{@link com.cardemo.service.card.CardUpdateService} &rarr;
 *       {@link #ACCT_ID_MAX_LENGTH}, {@link #CARD_NUM_MAX_LENGTH},
 *       {@link #MIN_EXPIRY_YEAR}, {@link #MAX_EXPIRY_YEAR},
 *       {@link #MIN_EXPIRY_MONTH}, {@link #MAX_EXPIRY_MONTH}</li>
 *   <li>{@code com.cardemo.domain.validation.CardValidator} (new domain class)</li>
 * </ul>
 *
 * <h3>Thread Safety</h3>
 * <p>All fields are primitive {@code int} literals and are therefore
 * immutable and safe to share across threads as {@code public static final}
 * singletons.</p>
 *
 * @see com.cardemo.service.card.CardUpdateService
 * @see com.cardemo.service.card.CardDetailService
 * @see com.cardemo.service.card.CardListService
 */
public final class CardConstants {

    /**
     * Maximum length for card number field &mdash; PIC X(16) from CVACT02Y.cpy.
     *
     * <p>Matches COBOL WORKING-STORAGE field width for 16-character card number
     * identifiers. Used by card validation (paragraph {@code 2220-EDIT-CARD} in
     * {@code COCRDLIC.cbl} / {@code COCRDUPC.cbl}) to enforce exact-length
     * constraints on card number inputs. Historically declared locally as
     * {@code CARD_NUM_MAX_LENGTH} in {@code CardDetailService} and
     * {@code CardUpdateService} and as {@code MAX_CARD_NUM_LENGTH} in
     * {@code CardListService}; centralizing here eliminates that duplication
     * per AAP &sect;0.7.4.</p>
     */
    public static final int CARD_NUM_MAX_LENGTH = 16;

    /**
     * Maximum length for account ID field &mdash; PIC 9(11) from CVACT02Y.cpy.
     *
     * <p>Matches COBOL WORKING-STORAGE field width for 11-digit account
     * identifiers referenced from the card entity. Used by card filter
     * validation (paragraph {@code 2210-EDIT-ACCOUNT} in {@code COCRDLIC.cbl})
     * to enforce the expected fixed-length constraint on account ID lookups.
     * Historically declared locally as {@code ACCT_ID_MAX_LENGTH} in
     * {@code CardDetailService} and {@code CardUpdateService} and as
     * {@code MAX_ACCT_ID_LENGTH} in {@code CardListService}; centralizing
     * here eliminates that duplication per AAP &sect;0.7.4.</p>
     */
    public static final int ACCT_ID_MAX_LENGTH = 11;

    /**
     * Minimum number of trailing digits shown when masking PII values for
     * logging.
     *
     * <p>When card numbers or account IDs appear in diagnostic log output,
     * only the trailing {@code MASK_VISIBLE_DIGITS} characters are shown
     * &mdash; the remaining digits are replaced with a masking character
     * (e.g., {@code '*'}). Observability rules per AAP &sect;0.7.1 require
     * card numbers never to be logged in full (PCI DSS). Historically declared
     * locally in {@code CardListService}; centralizing here makes the same
     * masking policy available to every card service uniformly.</p>
     */
    public static final int MASK_VISIBLE_DIGITS = 4;

    /**
     * Minimum valid expiration year per COBOL {@code 1260-EDIT-EXPIRY-YEAR}.
     *
     * <p>The inclusive lower bound of the valid card expiration year range
     * ({@code 1950}-{@code 2099}) used by card update validation in
     * {@code COCRDUPC.cbl} paragraph {@code 1260-EDIT-EXPIRY-YEAR}. Preserves
     * 100% behavioral parity with the COBOL program per AAP Rule R-001
     * &mdash; changing this value would alter the validation contract and
     * reject or accept expiry years inconsistent with the legacy screen.</p>
     */
    public static final int MIN_EXPIRY_YEAR = 1950;

    /**
     * Maximum valid expiration year per COBOL {@code 1260-EDIT-EXPIRY-YEAR}.
     *
     * <p>The inclusive upper bound of the valid card expiration year range
     * ({@code 1950}-{@code 2099}) used by card update validation in
     * {@code COCRDUPC.cbl} paragraph {@code 1260-EDIT-EXPIRY-YEAR}. Preserves
     * 100% behavioral parity with the COBOL program per AAP Rule R-001
     * &mdash; changing this value would alter the validation contract and
     * reject or accept expiry years inconsistent with the legacy screen.</p>
     */
    public static final int MAX_EXPIRY_YEAR = 2099;

    /**
     * Minimum valid expiration month per COBOL {@code 1250-EDIT-EXPIRY-MON}.
     *
     * <p>The inclusive lower bound of the valid card expiration month (calendar
     * month {@code 1} = January) used by card update validation in
     * {@code COCRDUPC.cbl} paragraph {@code 1250-EDIT-EXPIRY-MON}. Preserves
     * 100% behavioral parity with the COBOL program per AAP Rule R-001.</p>
     */
    public static final int MIN_EXPIRY_MONTH = 1;

    /**
     * Maximum valid expiration month per COBOL {@code 1250-EDIT-EXPIRY-MON}.
     *
     * <p>The inclusive upper bound of the valid card expiration month
     * (calendar month {@code 12} = December) used by card update validation
     * in {@code COCRDUPC.cbl} paragraph {@code 1250-EDIT-EXPIRY-MON}.
     * Preserves 100% behavioral parity with the COBOL program per AAP
     * Rule R-001.</p>
     */
    public static final int MAX_EXPIRY_MONTH = 12;

    /**
     * Private constructor prevents instantiation of this constant holder
     * class.
     *
     * @throws AssertionError always, to defend against reflection-based
     *                       instantiation attempts.
     */
    private CardConstants() {
        throw new AssertionError(
                "CardConstants is a constant holder and must not be instantiated.");
    }
}
