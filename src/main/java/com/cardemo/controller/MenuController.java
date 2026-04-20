package com.cardemo.controller;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cardemo.exception.ValidationException;
import com.cardemo.service.interfaces.MenuService;
import com.cardemo.service.menu.AdminMenuService;
import com.cardemo.service.menu.MainMenuService;

/**
 * REST controller providing menu metadata retrieval for navigation routing.
 *
 * <p>Replaces CICS BMS screens COMEN01 (main menu — 10 options from COMEN02Y.cpy)
 * and COADM01 (admin menu — 4 options from COADM02Y.cpy). Delegates to
 * {@link MenuService} for static menu option metadata (aggregating
 * {@link MainMenuService} and {@link AdminMenuService}). Returns JSON lists of
 * menu option records for client-side rendering.</p>
 *
 * <h3>COBOL Program Mapping:</h3>
 * <ul>
 *   <li>COMEN01C.cbl (282 lines) — BUILD-MENU-OPTIONS paragraph → {@code GET /api/menu/main}</li>
 *   <li>COADM01C.cbl (268 lines) — BUILD-MENU-OPTIONS paragraph → {@code GET /api/menu/admin}</li>
 * </ul>
 *
 * <p>Admin menu access control is enforced by this controller via a
 * {@link SecurityContextHolder}-based role check (see {@link #isAdmin()}).
 * The {@code GET /api/menu/admin} endpoint returns HTTP 403 Forbidden when
 * the authenticated principal does not hold {@code ROLE_ADMIN}, satisfying
 * the contract documented in {@code docs/api-contracts.md} §9.2. The
 * {@code GET /api/menu/main} endpoint remains accessible to any
 * authenticated principal regardless of role.</p>
 *
 * @see com.cardemo.service.interfaces.MenuService
 * @see MainMenuService.MenuOption
 * @see AdminMenuService.AdminMenuOption
 */
@RestController
@RequestMapping("/api/menu")
public class MenuController {

    private static final Logger logger = LoggerFactory.getLogger(MenuController.class);

    /**
     * Menu domain service contract aggregating main menu and admin menu metadata retrieval.
     * Spring injects the concrete implementation(s) in {@code com.cardemo.service.menu}
     * at runtime; the controller depends only on the interface.
     *
     * <p>Covers:</p>
     * <ul>
     *   <li><strong>COMEN01C.cbl (282 lines)</strong> — Main menu 10 options from
     *       COMEN02Y.cpy copybook (paragraph BUILD-MENU-OPTIONS).</li>
     *   <li><strong>COADM01C.cbl (268 lines)</strong> — Admin menu 4 options from
     *       COADM02Y.cpy copybook (paragraph BUILD-MENU-OPTIONS).</li>
     * </ul>
     */
    private final MenuService menuService;

    /**
     * Constructs a new {@code MenuController} with the required menu service dependency.
     *
     * <p>Spring auto-wires via single-constructor injection — no {@code @Autowired} annotation needed.</p>
     *
     * @param menuService the service contract aggregating main menu routing metadata
     *                    (10 options from COMEN02Y.cpy) and admin menu routing metadata
     *                    (4 options from COADM02Y.cpy)
     */
    public MenuController(MenuService menuService) {
        this.menuService = menuService;
    }

    /**
     * Retrieves menu options by menu type.
     *
     * <p>Maps the COBOL BUILD-MENU-OPTIONS paragraph from both COMEN01C.cbl (main menu)
     * and COADM01C.cbl (admin menu) to a single parameterized REST endpoint. The menu
     * type is extracted from the URI path variable and matched case-insensitively.</p>
     *
     * <h4>Supported menu types:</h4>
     * <ul>
     *   <li>{@code "main"} — Returns 10 main menu options (Account View, Account Update,
     *       Credit Card List, Credit Card View, Credit Card Update, Transaction List,
     *       Transaction View, Transaction Add, Transaction Reports, Bill Payment)</li>
     *   <li>{@code "admin"} — Returns 4 admin menu options (User List, User Add,
     *       User Update, User Delete)</li>
     * </ul>
     *
     * @param type the menu type path variable — must be "main" or "admin" (case-insensitive)
     * @return {@code ResponseEntity} containing:
     *         <ul>
     *           <li>HTTP 200 with {@code List<MenuOption>} for type "main"</li>
     *           <li>HTTP 200 with {@code List<AdminMenuOption>} for type "admin"
     *               (requires {@code ROLE_ADMIN})</li>
     *           <li>HTTP 400 with error message for any other type</li>
     *         </ul>
     * @throws AccessDeniedException when {@code type == "admin"} and the authenticated
     *         principal does not hold {@code ROLE_ADMIN}; mapped to HTTP 403 Forbidden
     *         by {@link com.cardemo.config.GlobalExceptionHandler}
     */
    @GetMapping("/{type}")
    public ResponseEntity<?> getMenu(@PathVariable String type) {
        logger.info("Retrieving {} menu", type);

        if ("main".equalsIgnoreCase(type)) {
            List<MainMenuService.MenuOption> menuOptions = menuService.getMenuOptions();
            return ResponseEntity.ok(menuOptions);
        }

        if ("admin".equalsIgnoreCase(type)) {
            // Contract: docs/api-contracts.md §9.2 Admin Menu — mandates 403 for
            // non-admin callers. We check the authenticated principal here rather
            // than relying on URL-pattern-based SecurityConfig rules so that the
            // admin-menu contract is enforced in the controller layer that owns
            // the path variable branching logic. COBOL parity: COADM01C.cbl
            // enforced admin-only access via the EIBTRNID / CDEMO-USRTYPE lookup
            // that gated the BUILD-MENU-OPTIONS paragraph — this isAdmin() check
            // is the REST-equivalent of that CDEMO-USRTYPE = 'A' guard.
            if (!isAdmin()) {
                logger.warn("Access denied: non-admin user attempted to retrieve admin menu");
                throw new AccessDeniedException("Access denied: ADMIN role required for admin menu");
            }
            List<AdminMenuService.AdminMenuOption> adminMenuOptions = menuService.getAdminMenuOptions();
            return ResponseEntity.ok(adminMenuOptions);
        }

        logger.info("Invalid menu type requested: {}", type);
        throw new ValidationException("Invalid menu type. Use 'main' or 'admin'.");
    }

    /**
     * Returns {@code true} when the current {@link SecurityContextHolder} principal
     * holds the {@code ROLE_ADMIN} granted authority.
     *
     * <p>Used to enforce the admin-menu authorization contract (see
     * {@code docs/api-contracts.md} §9.2) in the controller layer. Returns
     * {@code false} when no {@link Authentication} is present (e.g., when
     * Spring Security has not yet populated the context, which would have
     * already produced an HTTP 401 upstream) or when the authorities collection
     * does not contain {@code ROLE_ADMIN}.</p>
     *
     * <p>COBOL parity: in COADM01C.cbl, the equivalent gate inspected
     * {@code CDEMO-USRTYPE} (derived from USRSEC file field {@code SEC-USR-TYPE})
     * for the 'A' character. Here we inspect the SpringSecurity-derived
     * {@code ROLE_ADMIN} authority, which is assigned when {@code secUsrType == 'A'}
     * during authentication in {@code AuthenticationService.buildSignOnResponse()}.</p>
     *
     * @return {@code true} if the current authenticated user has {@code ROLE_ADMIN};
     *         {@code false} otherwise (including when no authentication is present)
     */
    private boolean isAdmin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return false;
        }
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            if ("ROLE_ADMIN".equals(authority.getAuthority())) {
                return true;
            }
        }
        return false;
    }
}
