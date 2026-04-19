/*
 * TransactionServiceImpl.java — Composite TransactionService Implementation
 *
 * Primary Spring-managed @Service bean exposing the unified
 * {@link com.cardemo.service.interfaces.TransactionService} contract.
 * This composite serves two purposes:
 *
 *   1. Eliminates the runtime fragility of
 *      {@link com.cardemo.controller.TransactionController} by providing a
 *      single {@code TransactionService} bean. Multiple concrete services
 *      in the transaction domain ({@link TransactionListService},
 *      {@link TransactionAddService}, and — in a future commit —
 *      TransactionDetailService) each declare
 *      {@code implements TransactionService}, which would otherwise
 *      create the same multi-bean ambiguity already resolved for
 *      {@code AdminService} and {@code MenuService}. This composite
 *      resolves the ambiguity by carrying the {@link Primary} annotation.
 *
 *   2. Gracefully degrades the one transaction-domain method whose
 *      concrete implementation has not yet been delivered to this
 *      branch (TransactionDetailService owns getTransaction). Instead of
 *      allowing the controller to dispatch to a stub, {@code getTransaction}
 *      throws {@link UnsupportedOperationException} directly from this
 *      composite with an explicit "not yet implemented on this branch"
 *      message. When TransactionDetailService lands on this branch, this
 *      composite's constructor will be extended to accept it and the
 *      throw statement will be replaced with a delegation.
 *
 * Routing below:
 *   listTransactions      -> TransactionListService
 *   hasNextPage           -> TransactionListService
 *   getPageNavigation     -> TransactionListService
 *   addTransaction        -> TransactionAddService
 *   copyFromTransaction   -> TransactionAddService
 *   getTransaction        -> UnsupportedOperationException (not on branch)
 *
 * AAP Rule Compliance:
 *   R-001 — No business logic is introduced; delegation only.
 *   R-004 — COBOL traceability (COTRN00C.cbl, COTRN01C.cbl, COTRN02C.cbl)
 *           is preserved via Javadoc {@code @see} references to the
 *           concrete service that owns each method.
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
 * three list-domain methods to {@link TransactionListService}, the
 * add and copy methods to {@link TransactionAddService}, and throws
 * {@link UnsupportedOperationException} from {@code getTransaction} whose
 * concrete owner (TransactionDetailService) is not present on this
 * branch.
 *
 * <p>The composite carries the {@link Primary} annotation so that Spring
 * selects it for {@code TransactionService} injection points even though
 * multiple concrete classes also declare
 * {@code implements TransactionService}. When TransactionDetailService
 * arrives, this composite must be extended to accept it as an additional
 * constructor parameter and the remaining throw statement must be
 * replaced with a delegation.</p>
 *
 * <p>This class contains no business logic. Five methods are one-line
 * delegations to the concrete services. The remaining method
 * ({@code getTransaction}) throws {@link UnsupportedOperationException}
 * with a message that explicitly names the concrete service that will
 * eventually own it, so that an operator reading the stack trace
 * understands both the cause (the service is not yet on this branch)
 * and the resolution (deliver the named service and extend this
 * composite).</p>
 *
 * @see TransactionService
 * @see TransactionListService
 * @see TransactionAddService
 */
@Service
@Primary
public class TransactionServiceImpl implements TransactionService {

    /**
     * Concrete implementation of {@code listTransactions},
     * {@code hasNextPage}, and {@code getPageNavigation}
     * (COTRN00C.cbl, CT00).
     */
    private final TransactionListService transactionListService;

    /**
     * Concrete implementation of {@code addTransaction} and
     * {@code copyFromTransaction} (COTRN02C.cbl, CT02 — add).
     */
    private final TransactionAddService transactionAddService;

    /**
     * Constructs the composite transaction service with the concrete
     * delegates currently present on the branch.
     *
     * <p>When {@code TransactionDetailService} (COTRN01C.cbl) is added
     * to the branch, extend this constructor to accept it as an
     * additional parameter and replace the throw statement in
     * {@link #getTransaction(String)} with a delegation to the new
     * service.</p>
     *
     * @param transactionListService delegate for the three list-domain
     *                               methods (COTRN00C.cbl)
     * @param transactionAddService  delegate for the add and copy
     *                               methods (COTRN02C.cbl)
     */
    public TransactionServiceImpl(
            TransactionListService transactionListService,
            TransactionAddService transactionAddService) {
        this.transactionListService = transactionListService;
        this.transactionAddService = transactionAddService;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Delegates to
     * {@link TransactionAddService#addTransaction(TransactionDto)} —
     * create-new transaction flow (COTRN02C.cbl, CT02 — add).</p>
     *
     * @see TransactionAddService#addTransaction(TransactionDto)
     */
    @Override
    public TransactionDto addTransaction(TransactionDto request) {
        return transactionAddService.addTransaction(request);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Delegates to
     * {@link TransactionAddService#copyFromTransaction(String)} —
     * copy-and-create transaction flow (COTRN02C.cbl, CT02 — add).</p>
     *
     * @see TransactionAddService#copyFromTransaction(String)
     */
    @Override
    public TransactionDto copyFromTransaction(String sourceTransactionId) {
        return transactionAddService.copyFromTransaction(sourceTransactionId);
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
