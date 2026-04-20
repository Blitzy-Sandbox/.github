/*
 * AdminServiceImpl.java — Composite AdminService Implementation
 *
 * Primary Spring-managed @Service bean exposing the unified
 * {@link com.cardemo.service.interfaces.AdminService} contract. This class
 * exists solely to resolve the Spring dependency-injection ambiguity that
 * arises because four concrete services each declare
 * {@code implements AdminService}:
 *
 *   - UserAddService    (COUSR01C.cbl, CICS transaction CU01)
 *   - UserDeleteService (COUSR03C.cbl, CICS transaction CU03)
 *   - UserListService   (COUSR00C.cbl, CICS transaction CU00)
 *   - UserUpdateService (COUSR02C.cbl, CICS transaction CU02)
 *
 * Without disambiguation, injecting {@code AdminService} into
 * {@code UserAdminController} produces {@code NoUniqueBeanDefinitionException}
 * at application startup. This composite carries the {@code @Primary}
 * annotation so that Spring selects it when a single {@code AdminService}
 * dependency is requested, while the four concrete beans remain available
 * for direct injection (they are still discoverable by their concrete
 * types).
 *
 * Each of the nine interface methods delegates to the single concrete
 * service that owns the real implementation of that method; all other
 * concrete services provide {@code UnsupportedOperationException} stubs
 * for methods they do not own (the "Interface-Contract Stubs" pattern
 * documented in {@code AdminService} JavaDoc). The routing below sends
 * every call to the owning concrete service:
 *
 *   addUser              -> UserAddService
 *   getUserForDelete     -> UserDeleteService
 *   deleteUser           -> UserDeleteService
 *   listUsers            -> UserListService
 *   listUsersFromId      -> UserListService
 *   hasNextPage          -> UserListService
 *   hasPreviousPage      -> UserListService
 *   getUserForUpdate     -> UserUpdateService
 *   updateUser           -> UserUpdateService
 *
 * AAP Rule Compliance:
 *   R-001 — No business logic is introduced; every method is a
 *           one-line delegation to the concrete owner.
 *   R-004 — COBOL traceability (COUSR01C/02C/03C/00C.cbl program codes
 *           and transaction IDs) is preserved via Javadoc {@code @see}
 *           references in the concrete implementations.
 *   R-006 — Method signatures match the AdminService interface exactly,
 *           including the {@code Page<?>} wildcard generics on
 *           {@link #hasNextPage(Page)} and
 *           {@link #hasPreviousPage(Page)}.
 *   §0.7.3 — Realises the "single AdminService interface for the admin
 *           domain" design by providing a unified, injectable
 *           implementation without requiring consumers to choose among
 *           the four underlying concrete services.
 *
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 * Licensed under the Apache License, Version 2.0
 */
package com.cardemo.service.admin;

import com.cardemo.model.dto.UserSecurityDto;
import com.cardemo.service.interfaces.AdminService;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;

/**
 * Composite {@link AdminService} implementation that delegates each
 * interface method to the single concrete service that owns its real
 * implementation.
 *
 * <p>The composite resolves the four-bean dependency-injection ambiguity
 * that would otherwise prevent the Spring application context from starting
 * when a consumer (e.g.,
 * {@link com.cardemo.controller.UserAdminController}) depends on the
 * {@code AdminService} interface. The {@link Primary} annotation causes
 * Spring to choose this bean for {@code AdminService} injection points while
 * the four concrete {@code @Service} beans remain available for direct
 * concrete-type injection.</p>
 *
 * <p>This class contains no business logic. Every method is a single-line
 * delegation to the owning concrete service. The owning service is the one
 * whose name maps to the COBOL program that originally implemented that
 * operation on the mainframe (for example, {@code addUser} delegates to
 * {@link UserAddService} because {@code UserAddService} is the Java port
 * of {@code COUSR01C.cbl}).</p>
 *
 * <p><strong>Transactional semantics:</strong> the {@code @Transactional}
 * annotations on the owning concrete services remain authoritative. Because
 * this composite merely dispatches to those concrete beans, Spring's
 * proxy-based transaction interceptor activates based on the concrete
 * service's annotations at the point of dispatch.</p>
 *
 * @see AdminService
 * @see UserAddService
 * @see UserDeleteService
 * @see UserListService
 * @see UserUpdateService
 */
@Service
@Primary
public class AdminServiceImpl implements AdminService {

    /** Concrete implementation of {@code addUser} (COUSR01C.cbl, CU01). */
    private final UserAddService userAddService;

    /**
     * Concrete implementation of {@code getUserForDelete} and
     * {@code deleteUser} (COUSR03C.cbl, CU03).
     */
    private final UserDeleteService userDeleteService;

    /**
     * Concrete implementation of {@code listUsers}, {@code listUsersFromId},
     * {@code hasNextPage}, and {@code hasPreviousPage}
     * (COUSR00C.cbl, CU00).
     */
    private final UserListService userListService;

    /**
     * Concrete implementation of {@code getUserForUpdate} and
     * {@code updateUser} (COUSR02C.cbl, CU02).
     */
    private final UserUpdateService userUpdateService;

    /**
     * Constructs the composite admin service with all four concrete
     * delegates.
     *
     * <p>Spring resolves each parameter by concrete type; this works even
     * though every concrete class also declares {@code implements
     * AdminService} because the concrete-type lookup is unambiguous.</p>
     *
     * @param userAddService    delegate for {@code addUser}
     * @param userDeleteService delegate for {@code getUserForDelete},
     *                          {@code deleteUser}
     * @param userListService   delegate for {@code listUsers},
     *                          {@code listUsersFromId},
     *                          {@code hasNextPage},
     *                          {@code hasPreviousPage}
     * @param userUpdateService delegate for {@code getUserForUpdate},
     *                          {@code updateUser}
     */
    public AdminServiceImpl(UserAddService userAddService,
                            UserDeleteService userDeleteService,
                            UserListService userListService,
                            UserUpdateService userUpdateService) {
        this.userAddService = userAddService;
        this.userDeleteService = userDeleteService;
        this.userListService = userListService;
        this.userUpdateService = userUpdateService;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Delegates to {@link UserAddService#addUser(UserSecurityDto)}.</p>
     *
     * @see UserAddService#addUser(UserSecurityDto)
     */
    @Override
    public UserSecurityDto addUser(UserSecurityDto dto) {
        return userAddService.addUser(dto);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Delegates to
     * {@link UserDeleteService#getUserForDelete(String)}.</p>
     *
     * @see UserDeleteService#getUserForDelete(String)
     */
    @Override
    public UserSecurityDto getUserForDelete(String userId) {
        return userDeleteService.getUserForDelete(userId);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Delegates to {@link UserDeleteService#deleteUser(String)}.</p>
     *
     * @see UserDeleteService#deleteUser(String)
     */
    @Override
    public UserSecurityDto deleteUser(String userId) {
        return userDeleteService.deleteUser(userId);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Delegates to {@link UserListService#listUsers(int)}.</p>
     *
     * @see UserListService#listUsers(int)
     */
    @Override
    public Page<UserSecurityDto> listUsers(int pageNumber) {
        return userListService.listUsers(pageNumber);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Delegates to
     * {@link UserListService#listUsersFromId(String, int)}.</p>
     *
     * @see UserListService#listUsersFromId(String, int)
     */
    @Override
    public Page<UserSecurityDto> listUsersFromId(String startUserId,
                                                 int pageNumber) {
        return userListService.listUsersFromId(startUserId, pageNumber);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Delegates to {@link UserListService#hasNextPage(Page)}.
     * The {@code Page<?>} wildcard generic is preserved exactly per AAP
     * Rule R-006 so that the composite signature matches the
     * {@link AdminService} interface signature.</p>
     *
     * @see UserListService#hasNextPage(Page)
     */
    @Override
    public boolean hasNextPage(Page<?> page) {
        return userListService.hasNextPage(page);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Delegates to {@link UserListService#hasPreviousPage(Page)}.
     * The {@code Page<?>} wildcard generic is preserved exactly per AAP
     * Rule R-006.</p>
     *
     * @see UserListService#hasPreviousPage(Page)
     */
    @Override
    public boolean hasPreviousPage(Page<?> page) {
        return userListService.hasPreviousPage(page);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Delegates to
     * {@link UserUpdateService#getUserForUpdate(String)}.</p>
     *
     * @see UserUpdateService#getUserForUpdate(String)
     */
    @Override
    public UserSecurityDto getUserForUpdate(String userId) {
        return userUpdateService.getUserForUpdate(userId);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Delegates to
     * {@link UserUpdateService#updateUser(String, UserSecurityDto)}.</p>
     *
     * @see UserUpdateService#updateUser(String, UserSecurityDto)
     */
    @Override
    public UserSecurityDto updateUser(String userId, UserSecurityDto dto) {
        return userUpdateService.updateUser(userId, dto);
    }
}
