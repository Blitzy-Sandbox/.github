/*
 * MenuServiceImpl.java — Composite MenuService Implementation
 *
 * Primary Spring-managed @Service bean exposing the unified
 * {@link com.cardemo.service.interfaces.MenuService} contract. This class
 * exists solely to resolve the Spring dependency-injection ambiguity that
 * arises because two concrete services each declare
 * {@code implements MenuService}:
 *
 *   - MainMenuService  (COMEN01C.cbl, CICS transaction CM00 — regular user)
 *   - AdminMenuService (COADM01C.cbl, CICS transaction CA00 — admin user)
 *
 * Without disambiguation, injecting {@code MenuService} into
 * {@code MenuController} produces {@code NoUniqueBeanDefinitionException}
 * at application startup. This composite carries the {@code @Primary}
 * annotation so that Spring selects it when a single {@code MenuService}
 * dependency is requested, while the two concrete beans remain available
 * for direct injection.
 *
 * Routing below sends each call to the single concrete service that owns
 * the method's domain:
 *
 *   getMenuOptions            -> MainMenuService   (regular-user menu)
 *   getMenuOptionsForUser     -> MainMenuService   (regular-user menu)
 *   getMenuOption             -> MainMenuService   (regular-user menu)
 *   isOptionAccessible        -> MainMenuService   (regular-user menu)
 *   getAdminMenuOptions       -> AdminMenuService  (admin-user menu)
 *   getAdminMenuOption        -> AdminMenuService  (admin-user menu)
 *   getOptionCount            -> UnsupportedOperationException  (ambiguous: 10 vs 4)
 *   isAdminOnly               -> UnsupportedOperationException  (ambiguous: false vs true)
 *
 * The two overlap methods ({@code getOptionCount}, {@code isAdminOnly})
 * are implemented by BOTH concrete services with different correct return
 * values (MainMenuService: 10, false; AdminMenuService: 4, true). There
 * is no single deterministic answer at the composite level — the caller
 * must choose the specific concrete service if either value is needed. In
 * the current codebase, {@link com.cardemo.controller.MenuController} only
 * calls {@link #getMenuOptions()} and {@link #getAdminMenuOptions()} on
 * the interface, so the composite never exercises the throw paths at
 * runtime. The stubs exist to satisfy the interface contract and to make
 * the ambiguity explicit to future callers rather than silently returning
 * a wrong value.
 *
 * AAP Rule Compliance:
 *   R-001 — No business logic is introduced; every non-throwing method is
 *           a one-line delegation to the concrete owner.
 *   R-004 — COBOL traceability (COMEN01C.cbl and COADM01C.cbl) is preserved
 *           via Javadoc {@code @see} references to the concrete services.
 *   R-006 — Method signatures match the MenuService interface exactly.
 *   §0.7.3 — Realises the "single MenuService interface for the menu
 *           domain" design by providing a unified, injectable
 *           implementation.
 *
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 * Licensed under the Apache License, Version 2.0
 */
package com.cardemo.service.menu;

import com.cardemo.model.enums.UserType;
import com.cardemo.service.interfaces.MenuService;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Composite {@link MenuService} implementation that delegates each
 * interface method to the single concrete service that owns its domain.
 *
 * <p>The composite resolves the two-bean dependency-injection ambiguity
 * that would otherwise prevent the Spring application context from starting
 * when a consumer (e.g.,
 * {@link com.cardemo.controller.MenuController}) depends on the
 * {@code MenuService} interface. The {@link Primary} annotation causes
 * Spring to choose this bean for {@code MenuService} injection points
 * while the two concrete {@code @Service} beans remain available for
 * direct concrete-type injection.</p>
 *
 * <p>This class contains no business logic. Six methods are one-line
 * delegations to the domain-owning concrete service. The two remaining
 * methods ({@code getOptionCount} and {@code isAdminOnly}) throw
 * {@link UnsupportedOperationException} because each concrete service
 * correctly implements them with a <em>different</em> domain-correct
 * value; there is no single deterministic answer that the composite can
 * return without silently picking one menu's value over the other.
 * Callers that need either value must inject
 * {@link MainMenuService} or {@link AdminMenuService} directly.</p>
 *
 * @see MenuService
 * @see MainMenuService
 * @see AdminMenuService
 */
@Service
@Primary
public class MenuServiceImpl implements MenuService {

    /**
     * Concrete implementation owning the regular-user main-menu domain
     * (COMEN01C.cbl, CICS transaction CM00).
     */
    private final MainMenuService mainMenuService;

    /**
     * Concrete implementation owning the admin-user admin-menu domain
     * (COADM01C.cbl, CICS transaction CA00).
     */
    private final AdminMenuService adminMenuService;

    /**
     * Constructs the composite menu service with both concrete delegates.
     *
     * <p>Spring resolves each parameter by concrete type; this works even
     * though both concrete classes also declare
     * {@code implements MenuService} because the concrete-type lookup is
     * unambiguous.</p>
     *
     * @param mainMenuService  delegate for regular-user main-menu methods
     *                         ({@code getMenuOptions},
     *                         {@code getMenuOptionsForUser},
     *                         {@code getMenuOption},
     *                         {@code isOptionAccessible})
     * @param adminMenuService delegate for admin-user admin-menu methods
     *                         ({@code getAdminMenuOptions},
     *                         {@code getAdminMenuOption})
     */
    public MenuServiceImpl(MainMenuService mainMenuService,
                           AdminMenuService adminMenuService) {
        this.mainMenuService = mainMenuService;
        this.adminMenuService = adminMenuService;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Delegates to {@link MainMenuService#getMenuOptions()} — the
     * regular-user main-menu option list is owned by
     * {@code MainMenuService} (COMEN01C.cbl BUILD-MENU-OPTIONS,
     * lines 236-277).</p>
     *
     * @see MainMenuService#getMenuOptions()
     */
    @Override
    public List<MainMenuService.MenuOption> getMenuOptions() {
        return mainMenuService.getMenuOptions();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Delegates to
     * {@link MainMenuService#getMenuOptionsForUser(UserType)} — role-based
     * main-menu filtering is owned by {@code MainMenuService}
     * (COMEN01C.cbl PROCESS-ENTER-KEY access check, lines 136-143).</p>
     *
     * @see MainMenuService#getMenuOptionsForUser(UserType)
     */
    @Override
    public List<MainMenuService.MenuOption> getMenuOptionsForUser(
            UserType userType) {
        return mainMenuService.getMenuOptionsForUser(userType);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Delegates to {@link MainMenuService#getMenuOption(int)} —
     * single-option lookup for the regular-user main menu
     * (COMEN01C.cbl PROCESS-ENTER-KEY option validation,
     * lines 127-134).</p>
     *
     * @see MainMenuService#getMenuOption(int)
     */
    @Override
    public MainMenuService.MenuOption getMenuOption(int optionNumber) {
        return mainMenuService.getMenuOption(optionNumber);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Both {@link MainMenuService#getOptionCount()} (returns
     * {@code 10} — {@code CDEMO-MENU-OPT-COUNT}) and
     * {@link AdminMenuService#getOptionCount()} (returns {@code 4} —
     * {@code CDEMO-ADMIN-OPT-COUNT}) provide correct, non-throwing
     * answers for their respective menus, but the two answers differ and
     * this composite cannot deterministically select one without knowing
     * the caller's intended menu domain. Rather than silently choose a
     * wrong value, throw {@link UnsupportedOperationException}. Callers
     * that need a specific menu's option count must inject the concrete
     * service directly.</p>
     *
     * @throws UnsupportedOperationException always, because the two
     *         concrete implementations return different correct values
     *         (10 vs 4) for their respective menus.
     */
    @Override
    public int getOptionCount() {
        throw new UnsupportedOperationException(
                "getOptionCount is menu-specific (MainMenuService=10, "
                        + "AdminMenuService=4). Inject MainMenuService or "
                        + "AdminMenuService directly to obtain the count "
                        + "for a specific menu.");
    }

    /**
     * {@inheritDoc}
     *
     * <p>Delegates to
     * {@link MainMenuService#isOptionAccessible(int, UserType)} —
     * per-option access check for the regular-user main menu
     * (COMEN01C.cbl lines 136-143).</p>
     *
     * @see MainMenuService#isOptionAccessible(int, UserType)
     */
    @Override
    public boolean isOptionAccessible(int optionNumber, UserType userType) {
        return mainMenuService.isOptionAccessible(optionNumber, userType);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Delegates to {@link AdminMenuService#getAdminMenuOptions()} —
     * admin-user admin-menu option list is owned by
     * {@code AdminMenuService} (COADM01C.cbl BUILD-ADMIN-MENU-OPTIONS,
     * lines 226-263).</p>
     *
     * @see AdminMenuService#getAdminMenuOptions()
     */
    @Override
    public List<AdminMenuService.AdminMenuOption> getAdminMenuOptions() {
        return adminMenuService.getAdminMenuOptions();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Delegates to
     * {@link AdminMenuService#getAdminMenuOption(int)} — single-option
     * lookup for the admin-user admin menu (COADM01C.cbl option
     * validation).</p>
     *
     * @see AdminMenuService#getAdminMenuOption(int)
     */
    @Override
    public AdminMenuService.AdminMenuOption getAdminMenuOption(
            int optionNumber) {
        return adminMenuService.getAdminMenuOption(optionNumber);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Both {@link MainMenuService#isAdminOnly()} (returns
     * {@code false}) and {@link AdminMenuService#isAdminOnly()} (returns
     * {@code true}) provide correct, non-throwing answers for their
     * respective menus, but the two answers differ and this composite
     * cannot deterministically select one without knowing the caller's
     * intended menu domain. Rather than silently choose a wrong value,
     * throw {@link UnsupportedOperationException}. Callers that need this
     * answer must inject the concrete service directly.</p>
     *
     * @throws UnsupportedOperationException always, because the two
     *         concrete implementations return different correct values
     *         (false vs true) for their respective menus.
     */
    @Override
    public boolean isAdminOnly() {
        throw new UnsupportedOperationException(
                "isAdminOnly is menu-specific (MainMenuService=false, "
                        + "AdminMenuService=true). Inject MainMenuService or "
                        + "AdminMenuService directly to determine whether a "
                        + "specific menu is admin-only.");
    }
}
