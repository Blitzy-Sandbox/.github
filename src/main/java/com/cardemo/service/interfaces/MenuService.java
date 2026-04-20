/*
 * MenuService.java — Menu Domain Service Interface
 *
 * Public service contract for menu-metadata and access-control operations in
 * the CardDemo application. Aggregates the public methods of the two concrete
 * @Service classes under {@code com.cardemo.service.menu}:
 *   - MainMenuService  (COMEN01C.cbl, CICS transaction CM00 — regular-user menu)
 *   - AdminMenuService (COADM01C.cbl, CICS transaction CA00 — admin-user menu)
 *
 * This file implements AAP Section 0.5.1 "New Service Interface Files
 * (CREATE)" and follows the design rules in the folder-level AAP for
 * {@code service/interfaces/}. No business logic is expressed here —
 * this is a pure Java contract (no default methods, no annotations on
 * the type). Exceptions thrown by implementations are Java unchecked
 * exceptions and therefore do NOT appear in {@code throws} clauses,
 * only in Javadoc {@code @throws} tags for documentation.
 *
 * Nested record types ({@code MainMenuService.MenuOption} and
 * {@code AdminMenuService.AdminMenuOption}) remain defined on their
 * respective concrete service classes per the folder-level AAP
 * minimal-change rule; this interface references them by qualified name.
 *
 * AAP Rule Compliance:
 *   R-001 — No business logic; interface-only declarations
 *   R-004 — COBOL {@see} references preserved for traceability
 *   R-006 — Method signatures match concrete sources exactly
 *
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 * Licensed under the Apache License, Version 2.0
 */
package com.cardemo.service.interfaces;

import com.cardemo.model.enums.UserType;
import com.cardemo.service.menu.AdminMenuService;
import com.cardemo.service.menu.MainMenuService;

import java.util.List;

/**
 * Service contract for menu metadata and access-control operations in the CardDemo application.
 *
 * <p>This interface defines the public service boundary for the menu domain.
 * Implementations are provided by Spring-managed {@code @Service} classes in
 * {@code com.cardemo.service.menu}: {@link com.cardemo.service.menu.MainMenuService}
 * (the regular-user main menu) and {@link com.cardemo.service.menu.AdminMenuService}
 * (the admin-user admin menu). Consumers (e.g., {@code MenuController}) depend on
 * this interface rather than on concrete classes so that alternative implementations
 * may be supplied for testing (mocks) or future extension without modifying the
 * consumer code.</p>
 *
 * <p>The interface aggregates regular-user (main menu) and admin-user (admin menu)
 * metadata retrieval and option access-control across two concrete services, each
 * of which migrates one COBOL online program:</p>
 * <ul>
 *   <li>{@code MainMenuService}  — regular-user menu (COMEN01C.cbl, CM00)</li>
 *   <li>{@code AdminMenuService} — admin-user menu (COADM01C.cbl, CA00)</li>
 * </ul>
 *
 * <p>The eight methods declared here correspond to the public methods of these
 * two classes. The {@link #getOptionCount()} method is shared — both implementing
 * services expose it with an identical signature, and a single declaration on
 * this interface satisfies both contracts. No single concrete class implements
 * every method meaningfully: each concrete implementation is responsible for its
 * own menu domain and provides {@link UnsupportedOperationException} stubs (or
 * semantic defaults where factually correct) for the opposite domain's methods.
 * See the folder-level AAP note for the implementation strategy.</p>
 *
 * <h3>Nested Record Types (Minimal-Change Rule)</h3>
 * <p>Per the folder-level AAP minimal-change rule, the public nested record
 * types used as return types and element types on this interface remain defined
 * on their respective concrete service classes rather than being extracted:</p>
 * <ul>
 *   <li>{@code MainMenuService.MenuOption} — five fields: {@code optionNumber},
 *       {@code optionName}, {@code cobolProgram}, {@code apiEndpoint},
 *       {@code requiredUserType} (last field is {@code String}, mirroring the
 *       COBOL {@code CDEMO-MENU-OPT-USRTYPE PIC X(01)} single-character user
 *       type code "U" or "A")</li>
 *   <li>{@code AdminMenuService.AdminMenuOption} — four fields:
 *       {@code optionNumber}, {@code optionName}, {@code cobolProgram},
 *       {@code apiEndpoint} (no user-type field because all admin options
 *       are admin-only by definition — {@code COADM02Y.cpy} omits a
 *       per-option user-type column)</li>
 * </ul>
 * <p>This interface references both records by qualified name. The imports
 * bring in the concrete classes for these nested-type references.</p>
 *
 * <h3>Stateless Semantics (Implementation Concern)</h3>
 * <p>Both implementing services are stateless metadata providers with no
 * repository or external dependencies, so no transactional semantics apply to
 * this interface. The menu option lists are compile-time-immutable,
 * faithfully mirroring the COBOL compile-time VALUE tables
 * ({@code COMEN02Y.cpy} and {@code COADM02Y.cpy}).</p>
 *
 * <h3>COBOL Source References</h3>
 * <p>These references provide traceability back to the original mainframe
 * programs (see {@code TRACEABILITY_MATRIX.md} for the full paragraph-level
 * mapping):</p>
 *
 * @see com.cardemo.model.enums.UserType
 * @see com.cardemo.service.menu.MainMenuService
 * @see com.cardemo.service.menu.AdminMenuService
 * @see <a href="file:app/cbl/COMEN01C.cbl">COMEN01C.cbl</a> — Main Menu online transaction (CM00, regular user)
 * @see <a href="file:app/cpy/COMEN02Y.cpy">COMEN02Y.cpy</a> — Main Menu options table copybook (10 entries, OCCURS 12 TIMES)
 * @see <a href="file:app/cpy/COCOM01Y.cpy">COCOM01Y.cpy</a> — COMMAREA copybook defining menu navigation fields
 * @see <a href="file:app/cbl/COADM01C.cbl">COADM01C.cbl</a> — Admin Menu online transaction (CA00)
 * @see <a href="file:app/cpy/COADM02Y.cpy">COADM02Y.cpy</a> — Admin Menu options table copybook (4 entries)
 */
public interface MenuService {

    // ---------------------------------------------------------------------
    // Main Menu operations (from MainMenuService / COMEN01C.cbl)
    // ---------------------------------------------------------------------

    /**
     * Returns the complete unmodifiable list of all 10 main menu options.
     *
     * <p>Mirrors the {@code BUILD-MENU-OPTIONS} paragraph (COMEN01C.cbl
     * lines 236-277) which iterates from 1 to {@code CDEMO-MENU-OPT-COUNT}
     * (10) building the display text for each option. In the Java
     * implementation, the underlying static list replaces the runtime
     * string building with pre-defined metadata mirroring the compile-time
     * {@code COMEN02Y.cpy} VALUE table exactly.</p>
     *
     * <p>Implementation: {@link com.cardemo.service.menu.MainMenuService#getMenuOptions()}.
     * The admin-menu implementation ({@code AdminMenuService}) throws
     * {@link UnsupportedOperationException} for this method per the
     * interface-stub convention.</p>
     *
     * @return an unmodifiable list of all 10 {@link MainMenuService.MenuOption}
     *         entries
     * @see <a href="file:app/cbl/COMEN01C.cbl">COMEN01C.cbl</a>
     *      — BUILD-MENU-OPTIONS paragraph
     */
    List<MainMenuService.MenuOption> getMenuOptions();

    /**
     * Returns the menu options filtered by the specified user type.
     *
     * <p>Mirrors the user type access check in {@code PROCESS-ENTER-KEY}
     * (COMEN01C.cbl lines 136-143):</p>
     * <ul>
     *   <li>If {@code userType} is {@link UserType#ADMIN}: returns ALL
     *       options (admin users have unrestricted access).</li>
     *   <li>If {@code userType} is {@link UserType#USER}: returns only
     *       options where {@code requiredUserType} is {@code "U"}
     *       (filters out admin-only options).</li>
     * </ul>
     *
     * <p>Implementation: {@link com.cardemo.service.menu.MainMenuService#getMenuOptionsForUser(UserType)}.
     * The admin-menu implementation ({@code AdminMenuService}) throws
     * {@link UnsupportedOperationException} for this method per the
     * interface-stub convention.</p>
     *
     * @param userType the user type to filter by; must not be {@code null}
     * @return an unmodifiable filtered list of accessible
     *         {@link MainMenuService.MenuOption} entries
     * @throws IllegalArgumentException if {@code userType} is {@code null}
     * @see <a href="file:app/cbl/COMEN01C.cbl">COMEN01C.cbl</a>
     *      — PROCESS-ENTER-KEY access check (lines 136-143)
     */
    List<MainMenuService.MenuOption> getMenuOptionsForUser(UserType userType);

    /**
     * Retrieves a single menu option by its 1-based option number.
     *
     * <p>Mirrors the option validation in {@code PROCESS-ENTER-KEY}
     * (COMEN01C.cbl lines 127-134):</p>
     * <pre>
     *   IF WS-OPTION IS NOT NUMERIC OR
     *      WS-OPTION &gt; CDEMO-MENU-OPT-COUNT OR
     *      WS-OPTION = ZEROS
     *       MOVE 'Please enter a valid option number...' TO WS-MESSAGE
     * </pre>
     *
     * <p>Implementation: {@link com.cardemo.service.menu.MainMenuService#getMenuOption(int)}.
     * The admin-menu implementation ({@code AdminMenuService}) throws
     * {@link UnsupportedOperationException} for this method per the
     * interface-stub convention — admin options are resolved via
     * {@link #getAdminMenuOption(int)} instead.</p>
     *
     * @param optionNumber the 1-based option number (1 through 10)
     * @return the matching {@link MainMenuService.MenuOption}
     * @throws IllegalArgumentException if {@code optionNumber} is less
     *         than 1 or greater than the total option count (mirrors
     *         COBOL error message "Please enter a valid option number...")
     * @see <a href="file:app/cbl/COMEN01C.cbl">COMEN01C.cbl</a>
     *      — PROCESS-ENTER-KEY option validation (lines 127-134)
     */
    MainMenuService.MenuOption getMenuOption(int optionNumber);

    // ---------------------------------------------------------------------
    // Shared operation (both MainMenuService and AdminMenuService)
    // ---------------------------------------------------------------------

    /**
     * Returns the total count of active menu options for the underlying menu.
     *
     * <p>Both implementing services ({@link com.cardemo.service.menu.MainMenuService}
     * and {@link com.cardemo.service.menu.AdminMenuService}) expose this
     * method with an identical public signature, and a single declaration
     * on this interface satisfies both contracts.</p>
     *
     * <p>Implementation mapping:</p>
     * <ul>
     *   <li>{@code MainMenuService.getOptionCount()} returns 10 — mirrors
     *       {@code CDEMO-MENU-OPT-COUNT PIC 9(02) VALUE 10} from
     *       {@code COMEN02Y.cpy} line 21 (table has {@code OCCURS 12 TIMES}
     *       capacity but only 10 entries are active).</li>
     *   <li>{@code AdminMenuService.getOptionCount()} returns 4 — mirrors
     *       {@code CDEMO-ADMIN-OPT-COUNT PIC 9(02) VALUE 4} from
     *       {@code COADM02Y.cpy} line 20.</li>
     * </ul>
     *
     * @return the number of active options (10 for the main menu, 4 for
     *         the admin menu)
     * @see <a href="file:app/cpy/COMEN02Y.cpy">COMEN02Y.cpy</a>
     *      — CDEMO-MENU-OPT-COUNT field (main menu)
     * @see <a href="file:app/cpy/COADM02Y.cpy">COADM02Y.cpy</a>
     *      — CDEMO-ADMIN-OPT-COUNT field (admin menu)
     */
    int getOptionCount();

    /**
     * Checks whether a specific menu option is accessible to the given user type.
     *
     * <p>Mirrors the exact user type access check from {@code COMEN01C.cbl}
     * lines 136-143:</p>
     * <pre>
     *   IF CDEMO-USRTYP-USER AND
     *      CDEMO-MENU-OPT-USRTYPE(WS-OPTION) = 'A'
     *       SET ERR-FLG-ON TO TRUE
     *       MOVE 'No access - Admin Only option... ' TO WS-MESSAGE
     * </pre>
     *
     * <p>Access rules:</p>
     * <ul>
     *   <li>{@link UserType#ADMIN} users can access ALL options regardless
     *       of {@code requiredUserType}.</li>
     *   <li>{@link UserType#USER} users are denied access to options with
     *       {@code requiredUserType = "A"}.</li>
     *   <li>{@link UserType#USER} users can access options with
     *       {@code requiredUserType = "U"}.</li>
     * </ul>
     *
     * <p>Implementation: {@link com.cardemo.service.menu.MainMenuService#isOptionAccessible(int, UserType)}.
     * The admin-menu implementation ({@code AdminMenuService}) throws
     * {@link UnsupportedOperationException} for this method per the
     * interface-stub convention — admin-menu visibility is answered at
     * the menu level via {@link #isAdminOnly()} rather than per-option
     * because {@code COADM02Y.cpy} does not include a per-option user
     * type column.</p>
     *
     * @param optionNumber the 1-based option number (1 through 10)
     * @param userType     the user type requesting access; must not be
     *                     {@code null}
     * @return {@code true} if the user can access the option,
     *         {@code false} otherwise
     * @throws IllegalArgumentException if {@code optionNumber} is out of
     *         range or {@code userType} is {@code null}
     * @see <a href="file:app/cbl/COMEN01C.cbl">COMEN01C.cbl</a>
     *      — user-type access check (lines 136-143)
     */
    boolean isOptionAccessible(int optionNumber, UserType userType);

    // ---------------------------------------------------------------------
    // Admin Menu operations (from AdminMenuService / COADM01C.cbl)
    // ---------------------------------------------------------------------

    /**
     * Returns the complete unmodifiable list of all 4 admin menu options.
     *
     * <p>Mirrors the {@code BUILD-ADMIN-MENU-OPTIONS} paragraph
     * (COADM01C.cbl, lines 226-263 analogue) which iterates from 1 to
     * {@code CDEMO-ADMIN-OPT-COUNT} (4) building the display text for
     * each option. In the Java implementation, the underlying static list
     * replaces the runtime string building with pre-defined metadata
     * mirroring the compile-time {@code COADM02Y.cpy} VALUE table exactly.</p>
     *
     * <p>Implementation: {@link com.cardemo.service.menu.AdminMenuService#getAdminMenuOptions()}.
     * The main-menu implementation ({@code MainMenuService}) throws
     * {@link UnsupportedOperationException} for this method per the
     * interface-stub convention.</p>
     *
     * @return an unmodifiable list of all 4
     *         {@link AdminMenuService.AdminMenuOption} entries
     * @see <a href="file:app/cbl/COADM01C.cbl">COADM01C.cbl</a>
     *      — BUILD-ADMIN-MENU-OPTIONS paragraph
     * @see <a href="file:app/cpy/COADM02Y.cpy">COADM02Y.cpy</a>
     *      — CDEMO-ADMIN-OPT-COUNT field (4 options)
     */
    List<AdminMenuService.AdminMenuOption> getAdminMenuOptions();

    /**
     * Retrieves a single admin menu option by its 1-based option number.
     *
     * <p>Mirrors the admin option validation flow in {@code COADM01C.cbl}
     * (PROCESS-ENTER-KEY analogue). The validation error message format
     * matches the COBOL equivalent: {@code "Please enter a valid option
     * number..."} constrained to the admin-menu range 1-4.</p>
     *
     * <p>Implementation: {@link com.cardemo.service.menu.AdminMenuService#getAdminMenuOption(int)}.
     * The main-menu implementation ({@code MainMenuService}) throws
     * {@link UnsupportedOperationException} for this method per the
     * interface-stub convention — main-menu options are resolved via
     * {@link #getMenuOption(int)} instead.</p>
     *
     * @param optionNumber the 1-based admin option number (1 through 4)
     * @return the matching {@link AdminMenuService.AdminMenuOption}
     * @throws IllegalArgumentException if {@code optionNumber} is less
     *         than 1 or greater than the total admin option count
     *         (valid range 1-4)
     * @see <a href="file:app/cbl/COADM01C.cbl">COADM01C.cbl</a>
     *      — admin option validation
     */
    AdminMenuService.AdminMenuOption getAdminMenuOption(int optionNumber);

    /**
     * Indicates whether this menu requires admin user access.
     *
     * <p>Mirrors the COBOL user-type access routing documented in
     * {@code COSGN00C.cbl}, where the sign-on program examined
     * {@code CDEMO-USRTYP-ADMIN VALUE 'A'} on the {@code CSUSR01Y.cpy}
     * {@code SEC-USR-TYPE} field to decide whether to route the user to
     * the admin menu ({@code COADM01C}) or the regular main menu
     * ({@code COMEN01C}).</p>
     *
     * <p>Implementation mapping:</p>
     * <ul>
     *   <li>{@code AdminMenuService.isAdminOnly()} always returns
     *       {@code true} — the admin menu is by definition restricted
     *       to admin-type users.</li>
     *   <li>{@code MainMenuService.isAdminOnly()} always returns
     *       {@code false} — the main menu is accessible to regular users
     *       (per-option access control is still enforced via
     *       {@link #isOptionAccessible(int, UserType)} and
     *       {@link #getMenuOptionsForUser(UserType)}).</li>
     * </ul>
     *
     * @return {@code true} for the admin menu, {@code false} for the
     *         main menu
     * @see <a href="file:app/cbl/COSGN00C.cbl">COSGN00C.cbl</a>
     *      — CDEMO-USRTYP-ADMIN access routing
     */
    boolean isAdminOnly();
}
