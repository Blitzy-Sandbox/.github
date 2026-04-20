/*
 * TransactionServiceImpl.java — Composite TransactionService Implementation
 *
 * Primary Spring-managed @Service bean exposing the unified
 * {@link com.cardemo.service.interfaces.TransactionService} contract.
 * This composite serves one remaining purpose:
 *
 *   Eliminates the runtime fragility of
 *   {@link com.cardemo.controller.TransactionController} by providing a
 *   single {@code TransactionService} bean. All three concrete services
 *   in the transaction domain ({@link TransactionListService},
 *   {@link TransactionAddService}, and
 *   {@link TransactionDetailService}) each declare
 *   {@code implements TransactionService}, which would otherwise
 *   create the same multi-bean ambiguity already resolved for
 *   {@code AdminService} and {@code MenuService}. This composite
 *   resolves the ambiguity by carrying the {@link Primary} annotation
 *   and delegating every method call to the concrete service that owns
 *   it.
 *
 * Routing below:
 *   listTransactions      -> TransactionListService
 *   hasNextPage           -> TransactionListService
 *   getPageNavigation     -> TransactionListService
 *   addTransaction        -> TransactionAddService
 *   copyFromTransaction   -> TransactionAddService
 *   getTransaction        -> TransactionDetailService
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
 * add and copy methods to {@link TransactionAddService}, and the
 * single-transaction lookup method ({@code getTransaction}) to
 * {@link TransactionDetailService}.
 *
 * <p>The composite carries the {@link Primary} annotation so that Spring
 * selects it for {@code TransactionService} injection points even though
 * multiple concrete classes also declare
 * {@code implements TransactionService}.</p>
 *
 * <p>This class contains no business logic. All six interface methods
 * are one-line delegations to the concrete services that own them. The
 * delegation surface reflects the three-way split of the transaction
 * domain mirrored from the COBOL programs: COTRN00C (list/browse),
 * COTRN01C (detail view), and COTRN02C (add/copy).</p>
 *
 * @see TransactionService
 * @see TransactionListService
 * @see TransactionAddService
 * @see TransactionDetailService
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
     * Concrete implementation of {@code getTransaction}
     * (COTRN01C.cbl, CT01 — view a transaction by 16-digit ID).
     */
    private final TransactionDetailService transactionDetailService;

    /**
     * Constructs the composite transaction service with the three
     * concrete delegates that together cover the full transaction
     * domain: list/browse, add/copy, and detail view.
     *
     * @param transactionListService   delegate for the three list-domain
     *                                 methods (COTRN00C.cbl)
     * @param transactionAddService    delegate for the add and copy
     *                                 methods (COTRN02C.cbl)
     * @param transactionDetailService delegate for the detail-view
     *                                 method (COTRN01C.cbl)
     */
    public TransactionServiceImpl(
            TransactionListService transactionListService,
            TransactionAddService transactionAddService,
            TransactionDetailService transactionDetailService) {
        this.transactionListService = transactionListService;
        this.transactionAddService = transactionAddService;
        this.transactionDetailService = transactionDetailService;
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
     * <p>Delegates to
     * {@link TransactionDetailService#getTransaction(String)} — retrieve
     * a single transaction by its 16-digit transaction ID
     * (COTRN01C.cbl, CT01 — view).</p>
     *
     * @see TransactionDetailService#getTransaction(String)
     */
    @Override
    public TransactionDto getTransaction(String transactionId) {
        return transactionDetailService.getTransaction(transactionId);
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
