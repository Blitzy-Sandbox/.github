/*
 * BillingService.java — Bill Payment Service Interface
 *
 * Public service contract for online bill-payment workflows in the
 * CardDemo application. Wraps the single public method of the concrete
 * {@code @Service} class {@code com.cardemo.service.billing.BillPaymentService}
 * which migrates COBOL program COBIL00C.cbl (CICS transaction code CB00).
 *
 * This file implements AAP Section 0.5.1 "New Service Interface Files
 * (CREATE)" and follows the design rules in the folder-level AAP for
 * {@code service/interfaces/}. No business logic is expressed here —
 * this is a pure Java contract (no default methods, no annotations on
 * the type). Exceptions thrown by implementations are Java unchecked
 * exceptions and therefore do NOT appear in {@code throws} clauses,
 * only in Javadoc {@code @throws} tags for documentation.
 *
 * AAP Rule Compliance:
 *   R-001 — No business logic; interface-only declarations
 *   R-004 — COBOL {@see} reference preserved for traceability
 *   R-006 — Method signature matches concrete source exactly
 *           ({@code TransactionDto processPayment(String accountId)}
 *            at BillPaymentService.java line 283)
 *
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 * Licensed under the Apache License, Version 2.0
 */
package com.cardemo.service.interfaces;

import com.cardemo.model.dto.TransactionDto;

/**
 * Service contract for online bill-payment operations in the CardDemo application.
 *
 * <p>This interface defines the public service boundary for the billing domain.
 * The implementation is provided by the Spring-managed {@code @Service} class
 * {@code com.cardemo.service.billing.BillPaymentService}, which migrates the
 * legacy COBOL program {@code COBIL00C.cbl} (CICS transaction code {@code CB00}).
 * Consumers (e.g., {@code BillingController}) depend on this interface rather
 * than on the concrete class so that alternative implementations may be
 * supplied for testing (mocks) or future extension without modifying the
 * consumer code.</p>
 *
 * <p>The interface represents the bill-payment transaction flow as a single
 * atomic orchestration:</p>
 * <ol>
 *   <li>Retrieve the account record by the supplied account identifier
 *       (maps COBIL00C.cbl {@code READ-ACCTDAT-FILE}, lines 343-372).</li>
 *   <li>Validate that the account has a positive outstanding balance
 *       (maps {@code PROCESS-ENTER-KEY} lines 197-206:
 *       {@code IF ACCT-CURR-BAL <= ZEROS}).</li>
 *   <li>Validate credit-limit constraints to prevent violations on the
 *       resulting balance (AAP requirement, maps reject code 102).</li>
 *   <li>Resolve the card number from the account via the CXACAIX alternate
 *       index cross-reference (maps {@code READ-CXACAIX-FILE}, lines 408-436).</li>
 *   <li>Generate a new sequential 16-digit transaction ID (maps
 *       {@code STARTBR/READPREV/ENDBR} browse-to-end pattern, lines 441-505).</li>
 *   <li>Persist the bill-payment transaction record (maps
 *       {@code WRITE-TRANSACT-FILE}, lines 510-547).</li>
 *   <li>Update the account's current balance and cycle debit fields
 *       (maps {@code UPDATE-ACCTDAT-FILE}, lines 377-403).</li>
 * </ol>
 *
 * <h3>Transactional and Concurrency Semantics (Implementation Concern)</h3>
 * <p>Transactional behavior is a property of the implementing class, not of
 * this interface. The {@code BillPaymentService} implementation carries
 * method-level {@code @Transactional(rollbackFor = Exception.class)} on
 * {@link #processPayment(String)} to ensure atomicity across the account
 * balance update and the transaction record write — mapping the COBOL CICS
 * {@code READ UPDATE + WRITE + REWRITE} atomic sequence. Optimistic locking
 * via the {@code @Version} field on the {@code Account} entity replaces the
 * COBOL CICS {@code READ UPDATE} semantics.</p>
 *
 * <h3>Decimal Precision Constraint (Implementation Concern)</h3>
 * <p>All monetary operations in the implementation use {@code BigDecimal}
 * exclusively — zero {@code float}/{@code double} substitution per AAP
 * Section 0.8.2. Balance comparisons use {@code compareTo(BigDecimal.ZERO)},
 * never {@code equals()}, to avoid scale-sensitivity issues.</p>
 *
 * <h3>COBOL Source Reference</h3>
 * <p>This reference provides traceability back to the original mainframe
 * program (see {@code TRACEABILITY_MATRIX.md} for the full paragraph-level
 * mapping).</p>
 *
 * @see com.cardemo.model.dto.TransactionDto
 * @see <a href="file://app/cbl/COBIL00C.cbl">COBIL00C.cbl</a> — Bill Payment
 *      online transaction (CICS transaction code {@code CB00})
 */
public interface BillingService {

    /**
     * Processes a bill payment for the specified account, generating a payment
     * transaction and updating the account balance.
     *
     * <p>This method performs the full bill-payment orchestration: it looks up
     * the account, validates that it has an outstanding positive balance and
     * does not violate available-credit constraints, generates a new sequential
     * 16-digit transaction ID, persists the payment transaction record, and
     * updates the account's current balance and cycle debit fields. All steps
     * execute within a single transaction so that if any step fails after a
     * partial write, all changes are rolled back — preserving the atomicity
     * semantics of the COBOL CICS
     * {@code READ UPDATE + WRITE + REWRITE} sequence.</p>
     *
     * <p>Key COBOL paragraph mappings:</p>
     * <ul>
     *   <li>{@code PROCESS-ENTER-KEY} (COBIL00C.cbl lines 154-244) — entry-point
     *       orchestration</li>
     *   <li>{@code WRITE-TRANSACT-FILE} (lines 510-547) — transaction record
     *       persistence</li>
     *   <li>{@code UPDATE-ACCTDAT-FILE} (lines 377-403) — account balance
     *       update (REWRITE semantics)</li>
     * </ul>
     *
     * <p>The implementation is {@code @Transactional(rollbackFor = Exception.class)}
     * in {@code BillPaymentService}.</p>
     *
     * @param accountId the 11-digit numeric account identifier of the account
     *                  being paid (maps COBOL {@code ACCT-ID PIC 9(11)} from
     *                  CVACT01Y.cpy); must be non-null, non-blank, and exactly
     *                  11 numeric digits
     * @return the persisted {@link TransactionDto} representing the
     *         bill-payment transaction, populated with the generated 16-digit
     *         transaction ID and all 13 TRAN-RECORD fields from CVTRA05Y.cpy
     *         (350-byte layout) so that {@code BillingController} can render
     *         the payment confirmation without a separate lookup
     * @throws IllegalArgumentException if {@code accountId} is null, blank,
     *         or not 11 numeric digits (maps COBIL00C.cbl line 161
     *         "Acct ID can NOT be empty..." and empty/low-values check at
     *         line 159)
     * @throws com.cardemo.exception.RecordNotFoundException if no account
     *         matches the given ID (maps COBIL00C.cbl line 361
     *         "Account ID NOT found..."), or the account has no associated
     *         card cross-reference (maps line 424 {@code DFHRESP(NOTFND)} on
     *         the CXACAIX read)
     * @throws IllegalStateException if the account has a zero or non-positive
     *         current balance — i.e., there is nothing to pay (maps
     *         COBIL00C.cbl line 201 "You have nothing to pay...")
     * @throws com.cardemo.exception.CreditLimitExceededException if applying
     *         the payment would violate credit-limit constraints (AAP
     *         requirement; maps reject code 102)
     * @see <a href="file://app/cbl/COBIL00C.cbl">COBIL00C.cbl</a>
     *      {@code PROCESS-ENTER-KEY} paragraph and
     *      {@code WRITE-TRANSACT-FILE} / {@code UPDATE-ACCTDAT-FILE}
     *      paragraphs
     */
    TransactionDto processPayment(String accountId);
}
