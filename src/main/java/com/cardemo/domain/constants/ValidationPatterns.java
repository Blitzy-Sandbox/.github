package com.cardemo.domain.constants;

import java.util.regex.Pattern;

/**
 * Centralized constant holder for shared regular-expression {@link Pattern}
 * instances used by field validation logic across the CardDemo service and
 * domain layers.
 *
 * <p>Consolidates regex pattern String declarations that were previously
 * embedded in {@link com.cardemo.service.card.CardUpdateService} (and
 * potentially other validation sites). This class exposes the patterns as
 * precompiled {@link Pattern} instances — per the AAP folder requirement —
 * rather than raw Strings, allowing validators to call
 * {@link Pattern#matcher(CharSequence)}.{@link java.util.regex.Matcher#matches() matches()}
 * without re-compiling the regex on every invocation.</p>
 *
 * <p>This class follows the Constant Holder Pattern described in AAP §0.4.3 —
 * a {@code public final} class with a {@code private} no-argument constructor
 * to prevent instantiation.</p>
 *
 * <h3>COBOL Source Traceability</h3>
 * <p>Character-class restrictions derive from COBOL PIC clause validations and
 * {@code INSPECT CONVERTING} statements in the online card update program
 * {@code COCRDUPC.cbl} (paragraph {@code 1230-EDIT-NAME}) and the account
 * update program {@code COACTUPC.cbl}.</p>
 *
 * <h3>Consuming Classes (post-refactoring)</h3>
 * <ul>
 *   <li>{@code com.cardemo.service.card.CardUpdateService}</li>
 *   <li>{@code com.cardemo.domain.validation.CardValidator}</li>
 *   <li>{@code com.cardemo.domain.validation.AccountValidator}</li>
 *   <li>{@code com.cardemo.domain.validation.UserValidator}</li>
 * </ul>
 *
 * <h3>Thread Safety</h3>
 * <p>{@link Pattern} instances are immutable and thread-safe once compiled,
 * making them safe to share as {@code public static final} singletons.
 * Matchers produced from these patterns are NOT thread-safe and must be
 * created per-invocation via {@link Pattern#matcher(CharSequence)}.</p>
 *
 * @see com.cardemo.service.card.CardUpdateService
 */
public final class ValidationPatterns {

    /**
     * Regex matching strings composed entirely of ASCII digits
     * ({@code ^\d+$}).
     *
     * <p>Used for numeric-only field validation (account IDs, card numbers,
     * SSN digit-blocks, phone digits, FICO scores, etc.) equivalent to the
     * COBOL {@code IS NUMERIC} WORKING-STORAGE condition check. The pattern
     * rejects empty strings, leading signs, decimal points, spaces, and any
     * non-ASCII-digit character.</p>
     */
    public static final Pattern NUMERIC_PATTERN = Pattern.compile("^\\d+$");

    /**
     * Regex matching strings composed of ASCII letters and spaces
     * ({@code ^[A-Za-z ]+$}).
     *
     * <p>Used for cardholder-name and customer-name field validation,
     * equivalent to the COBOL {@code 1230-EDIT-NAME} paragraph's
     * {@code INSPECT CONVERTING} rule in {@code COCRDUPC.cbl}. The pattern
     * allows uppercase and lowercase ASCII letters plus ASCII space, but
     * rejects digits, hyphens, apostrophes, and any other punctuation.</p>
     */
    public static final Pattern ALPHA_SPACE_PATTERN = Pattern.compile("^[A-Za-z ]+$");

    /**
     * Private constructor prevents instantiation of this constant holder class.
     *
     * @throws AssertionError always, to defend against reflection-based instantiation.
     */
    private ValidationPatterns() {
        throw new AssertionError("ValidationPatterns is a constant holder and must not be instantiated.");
    }
}
