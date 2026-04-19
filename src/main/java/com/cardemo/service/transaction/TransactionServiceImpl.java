/*
 * TransactionServiceImpl.java — Composite TransactionService Implementation
 *
 * Primary Spring-managed @Service bean exposing the unified
 * {@link com.cardemo.service.interfaces.TransactionService} contract.
 * This composite serves two purposes:
 *
 *   1. Eliminates the runtime fragility of
 *      {@link com.cardemo.controller.TransactionController} by providing a
 *      single {@code TransactionService} bean. Currently, only
 *      {@link TransactionListService} is present on this branch and
 *      declares {@code implements TransactionService}; future commits will
 *      add TransactionAddService and TransactionDetailService, which will
 *      create the same multi-bean ambiguity already resolved for
 *      {@code AdminService} and {@code MenuService}. Delivering this
 *      composite now resolves the ambiguity proactively so that those
 *      future additions require no controller changes.
 *
 *   2. Gracefully degrades the three transaction-domain methods whose
 *      concrete implementations have not yet been delivered to this
 *      branch (TransactionAddService owns addTransaction/copyFromTransaction;
 *      TransactionDetailService owns getTransaction). Instead of allowing
 *      the controller to dispatch to a stub that throws
 *      {@code UnsupportedOperationException} and thereby map to an HTTP 500,
 *      those methods throw {@link UnsupportedOperationException} directly
 *      from this composite — the same runtime outcome as the current
 *      stub-on-TransactionListService behaviour, but with a single,
 *      explicit "not yet implemented on this branch" message. When
 *      TransactionAddService and TransactionDetailService land on this
 *      branch, this composite's constructor will be extended to accept
 *      them and the three throw statements will be replaced with
 *      delegations.
 *
 * Routing below:
 *   listTransactions      -> TransactionListService
 *   hasNextPage           -> TransactionListService
 *   getPageNavigation     -> TransactionListService
 *   addTransaction        -> UnsupportedOperationException (not on branch)
 *   copyFromTransaction   -> UnsupportedOperationException (not on branch)
 *   getTransaction        -> UnsupportedOperationException (not on branch)
 *
 * AAP Rule Compliance:
 *   R-001 — No business logic is introduced; delegation only.
 *   R-004 — COBOL traceability (COTRN00C.cbl, COTRN01C.cbl, COTRN02C.cbl)
 *           is preserved via Javadoc {@code @see} references to the
 *           concrete service whose eventual arrival will replace each
 *           not-implemented stub.
 *   R-006 — Method signatures match the TransactionService interface
 *           exactly, including the TYPED {@code Page<TransactionDto>}
 *           generic on {@link #hasNextPage(Page)} and
 *           {@link #getPageNavigation(Page)} — this is the stricter
 *           generic used by TransactionService (as opposed to the wildcard
 *           {@code Page<?>} used by AdminService).
 *   §0.7.3 — Realises the "single TransactionService interface for the
 *           transaction domain" design by providing a unified, injectable
 *           implementation.
 *
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 * Licensed under the Apache License, Version 2.0
 */
package com.cardemo.service.transaction;

import com.cardemo.model.dto.TransactionDto;
import com.cardemo.service.interfaces.TransactionService;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;

/**
 * Composite {@link TransactionService} implementation that delegates the
 * three list-domain methods to {@link TransactionListService} and throws
 * {@link UnsupportedOperationException} from the three add/detail methods
 * whose concrete owners are not present on this branch.
 *
 * <p>The composite carries the {@link Primary} annotation so that Spring
 * selects it for {@code TransactionService} injection points even after
 * future commits add TransactionAddService and TransactionDetailService
 * (each of which will also declare {@code implements TransactionService}).
 * When those classes land, this composite must be extended to accept them
 * as constructor parameters and the three throw statements must be
 * replaced with delegations to the new services.</p>
 *
 * <p>This class contains no business logic. Three methods are one-line
 * delegations to {@link TransactionListService}. The other three throw
 * {@link UnsupportedOperationException} with a message that explicitly
 * names the concrete service that will eventually own the method, so
 * that an operator reading the stack trace understands both the cause
 * (the service is not yet on this branch) and the resolution (deliver
 * the named service and extend this composite).</p>
 *
 * @see TransactionService
 * @see TransactionListService
 */
@Service
@Primary
public class TransactionServiceImpl implements TransactionService {

    /**
     * Concrete implementation of {@code listTransactions},
     * {@code hasNextPage}, and {@code getPageNavigation}
     * (COTRN00C.cbl, CT00). The only transaction-domain concrete service
     * currently on this branch.
     */
    private final TransactionListService transactionListService;

    /**
     * Constructs the composite transaction service with the only concrete
     * delegate currently present on the branch.
     *
     * <p>When {@code TransactionAddService} (COTRN02C.cbl) and
     * {@code TransactionDetailService} (COTRN01C.cbl) are added to the
     * branch, extend this constructor to accept them as additional
     * parameters and replace the throw statements in
     * {@link #addTransaction(TransactionDto)},
     * {@link #copyFromTransaction(String)}, and
     * {@link #getTransaction(String)} with delegations to the new
     * services.</p>
     *
     * @param transactionListService delegate for the three list-domain
     *                               methods
     */
    public TransactionServiceImpl(
            TransactionListService transactionListService) {
        this.transactionListService = transactionListService;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Not implemented on this branch. When
     * {@code TransactionAddService} (COTRN02C.cbl, CT02 — add) is
     * added, replace this stub with a delegation to its
     * {@code addTransaction} method.</p>
     *
     * @throws UnsupportedOperationException always, because
     *         {@code TransactionAddService} is not yet present on this
     *         branch.
     */
    @Override
    public TransactionDto addTransaction(TransactionDto request) {
        throw new UnsupportedOperationException(
                "addTransaction is owned by TransactionAddService "
                        + "(COTRN02C.cbl, CT02), which is not yet present "
                        + "on this branch. Deliver TransactionAddService "
                        + "and extend TransactionServiceImpl to delegate "
                        + "to it.");
    }

    /**
     * {@inheritDoc}
     *
     * <p>Not implemented on this branch. When
     * {@code TransactionAddService} (COTRN02C.cbl, CT02 — add) is
     * added, replace this stub with a delegation to its
     * {@code copyFromTransaction} method.</p>
     *
     * @throws UnsupportedOperationException always, because
     *         {@code TransactionAddService} is not yet present on this
     *         branch.
     */
    @Override
    public TransactionDto copyFromTransaction(String sourceTransactionId) {
        throw new UnsupportedOperationException(
                "copyFromTransaction is owned by TransactionAddService "
                        + "(COTRN02C.cbl, CT02), which is not yet present "
                        + "on this branch. Deliver TransactionAddService "
                        + "and extend TransactionServiceImpl to delegate "
                        + "to it.");
    }

    /**
     * {@inheritDoc}
     *
     * <p>Not implemented on this branch. When
     * {@code TransactionDetailService} (COTRN01C.cbl, CT01 — view) is
     * added, replace this stub with a delegation to its
     * {@code getTransaction} method.</p>
     *
     * @throws UnsupportedOperationException always, because
     *         {@code TransactionDetailService} is not yet present on this
     *         branch.
     */
    @Override
    public TransactionDto getTransaction(String transactionId) {
        throw new UnsupportedOperationException(
                "getTransaction is owned by TransactionDetailService "
                        + "(COTRN01C.cbl, CT01), which is not yet present "
                        + "on this branch. Deliver TransactionDetailService "
                        + "and extend TransactionServiceImpl to delegate "
                        + "to it.");
    }

    /**
     * {@inheritDoc}
     *
     * <p>Delegates to
     * {@link TransactionListService#listTransactions(String, int)} —
     * paginated browse with optional starting-ID filter (COTRN00C.cbl
     * PROCESS-PAGE-FORWARD, lines 279-328).</p>
     *
     * @see TransactionListService#listTransactions(String, int)
     */
    @Override
    public Page<TransactionDto> listTransactions(String startTransactionId,
                                                 int page) {
        return transactionListService.listTransactions(startTransactionId,
                page);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Delegates to
     * {@link TransactionListService#hasNextPage(Page)}. The stricter
     * TYPED generic {@code Page<TransactionDto>} (as opposed to the
     * wildcard {@code Page<?>} on AdminService) is preserved exactly per
     * AAP Rule R-006.</p>
     *
     * @see TransactionListService#hasNextPage(Page)
     */
    @Override
    public boolean hasNextPage(Page<TransactionDto> currentPage) {
        return transactionListService.hasNextPage(currentPage);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Delegates to
     * {@link TransactionListService#getPageNavigation(Page)}. The
     * {@link TransactionListService.PageNavigation} return type is the
     * concrete nested record on TransactionListService (preserved there
     * per the folder-level AAP minimal-change rule).</p>
     *
     * @see TransactionListService#getPageNavigation(Page)
     * @see TransactionListService.PageNavigation
     */
    @Override
    public TransactionListService.PageNavigation getPageNavigation(
            Page<TransactionDto> page) {
        return transactionListService.getPageNavigation(page);
    }
}
