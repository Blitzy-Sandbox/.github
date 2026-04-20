package com.cardemo.domain.constants;

/**
 * Centralized constant holder for the account domain.
 *
 * <p>Consolidates {@code static final} field declarations that were previously
 * duplicated across {@link com.cardemo.service.account.AccountUpdateService}
 * and {@link com.cardemo.service.account.AccountViewService}. This class
 * follows the Constant Holder Pattern described in AAP &sect;0.4.3 &mdash; a
 * {@code public final} class with a {@code private} no-argument constructor
 * to prevent instantiation and subclassing.</p>
 *
 * <h3>COBOL Source Traceability</h3>
 * <p>Account field widths and validation ranges originate from the COBOL
 * copybook {@code CVACT01Y.cpy} (account master record) as referenced in
 * {@code TRACEABILITY_MATRIX.md}. The FICO credit-score range and its
 * validation rule originate from the COBOL online account update program
 * {@code COACTUPC.cbl} paragraph {@code 1275-EDIT-FICO-SCORE}. The
 * all-zeros account-ID sentinel originates from the online account view
 * program {@code COACTVWC.cbl} paragraph {@code 2210-EDIT-ACCOUNT}. SSN
 * digit count mirrors the three-sub-field {@code PIC 9(03)/9(02)/9(04)}
 * layout from {@code CVCUS01Y.cpy}, and phone digit count mirrors the
 * NANPA 10-digit convention used by customer validation.</p>
 *
 * <h3>Consuming Classes (post-refactoring)</h3>
 * <ul>
 *   <li>{@link com.cardemo.service.account.AccountUpdateService} &rarr;
 *       {@link #ACCOUNT_ID_LENGTH}, {@link #SSN_LENGTH},
 *       {@link #PHONE_DIGITS_LENGTH}, {@link #FICO_MIN},
 *       {@link #FICO_MAX}</li>
 *   <li>{@link com.cardemo.service.account.AccountViewService} &rarr;
 *       {@link #ACCOUNT_ID_LENGTH}, {@link #ALL_ZEROS_ACCOUNT_ID}</li>
 *   <li>{@code com.cardemo.domain.validation.AccountValidator} (new domain
 *       class)</li>
 * </ul>
 *
 * <h3>Thread Safety</h3>
 * <p>All fields are primitive {@code int} literals or immutable
 * {@link String} instances and are therefore safe to share across threads as
 * {@code public static final} singletons. {@link String} is immutable by
 * language contract, and primitive constants are inlined by the Java
 * compiler into every call site.</p>
 *
 * <h3>Behavioral Parity (AAP Rule R-001)</h3>
 * <p>Each field preserves the exact value used by the legacy COBOL programs.
 * Changing any value would alter the validation contract and break 100%
 * behavioral parity with the original mainframe implementation.</p>
 *
 * @see com.cardemo.service.account.AccountUpdateService
 * @see com.cardemo.service.account.AccountViewService
 */
public final class AccountConstants {

    /**
     * COBOL ACCT-ID length: PIC X(11) / PIC 9(11).
     *
     * <p>Matches COBOL field width for 11-character account identifiers as
     * defined in {@code CVACT01Y.cpy}. Used by account validation
     * (paragraph {@code 2210-EDIT-ACCOUNT} in {@code COACTVWC.cbl} /
     * {@code COACTUPC.cbl}) to enforce exact-length constraints on account
     * ID inputs. Historically declared locally as {@code ACCOUNT_ID_LENGTH}
     * in both {@code AccountUpdateService} and {@code AccountViewService};
     * centralizing here eliminates that duplication per AAP &sect;0.7.4.</p>
     *
     * <p>Preserves 100% behavioral parity with the COBOL program per AAP
     * Rule R-001 &mdash; changing this value would reject or accept account
     * IDs inconsistent with the legacy screens.</p>
     */
    public static final int ACCOUNT_ID_LENGTH = 11;

    /**
     * All-zeros account ID representing an invalid zero-value input.
     *
     * <p>Maps the COBOL {@code ZEROES} literal check in paragraph
     * {@code 2210-EDIT-ACCOUNT} of {@code COACTVWC.cbl}. An account ID of
     * {@code "00000000000"} (exactly 11 zero characters, matching
     * {@link #ACCOUNT_ID_LENGTH}) is treated as uninitialized and must be
     * rejected during validation with the same
     * {@code "Account ID cannot be empty"}/{@code "Invalid account ID"}
     * semantics emitted by the legacy screen.</p>
     *
     * <p>Preserves 100% behavioral parity with the COBOL program per AAP
     * Rule R-001.</p>
     */
    public static final String ALL_ZEROS_ACCOUNT_ID = "00000000000";

    /**
     * COBOL SSN raw digits count: 3 + 2 + 4 = 9.
     *
     * <p>Matches the US Social Security Number digit length used by
     * customer validation in {@code AccountUpdateService}. The COBOL
     * layout uses three zero-padded fixed-width sub-fields
     * {@code PIC 9(03)}, {@code PIC 9(02)}, {@code PIC 9(04)} totaling
     * 9 digits. Validation rejects any input whose concatenated numeric
     * representation has a length other than this value.</p>
     *
     * <p>Preserves 100% behavioral parity with the COBOL program per AAP
     * Rule R-001.</p>
     */
    public static final int SSN_LENGTH = 9;

    /**
     * US phone number total digits: area(3) + prefix(3) + line(4) = 10.
     *
     * <p>Matches the NANPA (North American Numbering Plan Administration)
     * 10-digit phone number length enforced by the customer validation
     * logic in {@code AccountUpdateService}. Area-code values themselves
     * are validated against
     * {@code ValidationLookupService.isValidAreaCode(...)}, while this
     * constant governs the aggregate digit count of the concatenated
     * phone number field after any formatting characters have been
     * stripped.</p>
     *
     * <p>Preserves 100% behavioral parity with the COBOL program per AAP
     * Rule R-001.</p>
     */
    public static final int PHONE_DIGITS_LENGTH = 10;

    /**
     * FICO score minimum value per COBOL {@code 1275-EDIT-FICO-SCORE}.
     *
     * <p>The inclusive lower bound of the valid FICO credit score range
     * (300-850) used by customer FICO validation in {@code COACTUPC.cbl}
     * paragraph {@code 1275-EDIT-FICO-SCORE}. Any numeric score less than
     * this value is rejected with the same error semantics emitted by the
     * legacy screen.</p>
     *
     * <p>Preserves 100% behavioral parity with the COBOL program per AAP
     * Rule R-001 &mdash; changing this value would alter the validation
     * contract and either reject or accept FICO scores inconsistent with
     * the legacy screen.</p>
     */
    public static final int FICO_MIN = 300;

    /**
     * FICO score maximum value per COBOL {@code 1275-EDIT-FICO-SCORE}.
     *
     * <p>The inclusive upper bound of the valid FICO credit score range
     * (300-850) used by customer FICO validation in {@code COACTUPC.cbl}
     * paragraph {@code 1275-EDIT-FICO-SCORE}. Any numeric score greater
     * than this value is rejected with the same error semantics emitted
     * by the legacy screen.</p>
     *
     * <p>Preserves 100% behavioral parity with the COBOL program per AAP
     * Rule R-001 &mdash; changing this value would alter the validation
     * contract and either reject or accept FICO scores inconsistent with
     * the legacy screen.</p>
     */
    public static final int FICO_MAX = 850;

    /**
     * Private constructor prevents instantiation of this constant holder
     * class.
     *
     * <p>Throwing {@link AssertionError} defends against reflection-based
     * instantiation attempts (e.g., {@code Class.getDeclaredConstructor()}
     * followed by {@code setAccessible(true)}). A constant holder class
     * must never be instantiated &mdash; all access is via the
     * {@code public static final} fields.</p>
     *
     * @throws AssertionError always, to defend against reflection-based
     *                       instantiation attempts.
     */
    private AccountConstants() {
        throw new AssertionError(
                "AccountConstants is a constant holder and must not be instantiated.");
    }
}
