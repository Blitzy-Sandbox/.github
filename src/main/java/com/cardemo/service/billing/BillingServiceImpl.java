/*
 * BillingServiceImpl.java — Placeholder BillingService Implementation
 *
 * Spring-managed @Service bean exposing the
 * {@link com.cardemo.service.interfaces.BillingService} contract.
 * This implementation is a deliberate placeholder: the concrete
 * {@code BillPaymentService} (which migrates COBOL program
 * {@code COBIL00C.cbl}, CICS transaction code {@code CB00}) has not yet
 * been delivered to this branch. Without a bean implementing
 * {@code BillingService}, the Spring application context would fail to
 * start because {@link com.cardemo.controller.BillingController}
 * requires a {@code BillingService} injection.
 *
 * This placeholder allows the context to start cleanly while gracefully
 * reporting at request time that the bill-payment workflow is not yet
 * implemented. When {@code BillPaymentService} is added to this branch,
 * this file must be deleted and {@code BillPaymentService} — which will
 * declare {@code implements BillingService} — becomes the single
 * {@code BillingService} bean. No {@code @Qualifier} or {@code @Primary}
 * annotation is required at either point because exactly one bean will
 * be present at a time.
 *
 * AAP Rule Compliance:
 *   R-001 — No business logic is introduced; this class throws
 *           UnsupportedOperationException from its sole method.
 *   R-004 — COBOL traceability (COBIL00C.cbl) is preserved via the
 *           Javadoc {@code @see} reference and the exception message,
 *           which identifies the service whose eventual arrival will
 *           replace this placeholder.
 *   R-006 — The {@link #processPayment(String)} signature matches the
 *           BillingService interface (and the eventual BillPaymentService
 *           concrete source) exactly.
 *   §0.5.1 — Realises the "New Service Interface Files (CREATE)"
 *           requirement by ensuring BillingService has an implementation
 *           bean on this branch, matching the single-interface design
 *           goal while accommodating the staged delivery of concrete
 *           services.
 *
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 * Licensed under the Apache License, Version 2.0
 */
package com.cardemo.service.billing;

import com.cardemo.model.dto.TransactionDto;
import com.cardemo.service.interfaces.BillingService;
import org.springframework.stereotype.Service;

/**
 * Placeholder {@link BillingService} implementation used only while the
 * concrete {@code BillPaymentService} (COBIL00C.cbl, CICS transaction
 * code {@code CB00}) is absent from this branch.
 *
 * <p>The purpose of this class is strictly to allow the Spring
 * application context to start. Without a bean implementing
 * {@code BillingService}, {@link com.cardemo.controller.BillingController}
 * would fail to resolve its constructor dependency at startup
 * ({@code NoSuchBeanDefinitionException}), making the entire application
 * un-bootable. With this placeholder in place, the context starts
 * cleanly and the bill-payment endpoints return a deterministic 500-class
 * error at request time (via the global exception handler), rather than
 * preventing the application from starting at all.</p>
 *
 * <p>This class contains zero business logic. Its sole method throws
 * {@link UnsupportedOperationException} with a message that identifies
 * the concrete service ({@code BillPaymentService}) whose arrival will
 * replace this file. When that service lands, delete this file — the
 * concrete service will be the single {@code BillingService} bean and
 * no {@code @Qualifier}/{@code @Primary} disambiguation is required.</p>
 *
 * @see BillingService
 */
@Service
public class BillingServiceImpl implements BillingService {

    /**
     * {@inheritDoc}
     *
     * <p>Not implemented on this branch. When
     * {@code BillPaymentService} (COBIL00C.cbl, CICS transaction code
     * {@code CB00}) is added, this entire class must be deleted — the
     * concrete {@code BillPaymentService} will declare
     * {@code implements BillingService} and become the single
     * {@code BillingService} bean.</p>
     *
     * @throws UnsupportedOperationException always, because
     *         {@code BillPaymentService} is not yet present on this
     *         branch.
     */
    @Override
    public TransactionDto processPayment(String accountId) {
        throw new UnsupportedOperationException(
                "processPayment is owned by BillPaymentService "
                        + "(COBIL00C.cbl, CB00), which is not yet present "
                        + "on this branch. Deliver BillPaymentService and "
                        + "remove BillingServiceImpl (BillPaymentService "
                        + "will declare 'implements BillingService' and "
                        + "become the single BillingService bean).");
    }
}
