/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.cardemo.service.account;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import com.cardemo.model.dto.AccountDto;
import com.cardemo.service.interfaces.AccountService;

/**
 * Composite aggregator bean that resolves the bean ambiguity created by the
 * two concrete {@link AccountService} implementations in the
 * {@code com.cardemo.service.account} package:
 * {@link AccountViewService} (COBOL COACTVWC.cbl — CAVW transaction) and
 * {@link AccountUpdateService} (COBOL COACTUPC.cbl — CAUP transaction).
 *
 * <p>Each concrete {@code Account*Service} class implements the full
 * {@code AccountService} interface contract but provides real implementations
 * only for the method subset that maps to its COBOL program of origin; all
 * other interface methods are intentionally stubbed to throw
 * {@link UnsupportedOperationException} (the "Interface-Contract Stubs"
 * pattern). Without a disambiguator, Spring cannot autowire an
 * {@code AccountService} bean into {@code AccountController} because two
 * candidate beans satisfy the interface type.</p>
 *
 * <p>{@code AccountServiceImpl} is marked {@link Primary @Primary} so that
 * Spring selects it as the default {@code AccountService} injection target,
 * and it delegates each interface method to the concrete service that owns
 * the real implementation for that method. This mirrors the established
 * aggregator pattern already used in this codebase by
 * {@link com.cardemo.service.admin.AdminServiceImpl AdminServiceImpl},
 * {@link com.cardemo.service.card.CardServiceImpl CardServiceImpl},
 * {@link com.cardemo.service.menu.MenuServiceImpl MenuServiceImpl}, and
 * {@link com.cardemo.service.transaction.TransactionServiceImpl TransactionServiceImpl}.</p>
 *
 * <h3>Method Routing Table</h3>
 * <table>
 *   <caption>Maps each {@code AccountService} method to the concrete bean owning the real implementation</caption>
 *   <tr><th>Interface Method</th><th>Routed To</th><th>COBOL Program</th></tr>
 *   <tr><td>{@link #getAccountView(String)}</td>
 *       <td>{@code AccountViewService}</td>
 *       <td>COACTVWC.cbl (paragraph 9000-READ-ACCT — joins CXACAIX, ACCTDAT, CUSTDAT)</td></tr>
 *   <tr><td>{@link #getAccount(String)}</td>
 *       <td>{@code AccountUpdateService}</td>
 *       <td>COACTUPC.cbl (paragraph 9000-READ-ACCT — pre-update read-for-update)</td></tr>
 *   <tr><td>{@link #updateAccount(String, AccountDto)}</td>
 *       <td>{@code AccountUpdateService}</td>
 *       <td>COACTUPC.cbl (paragraphs PROCESS-UPDATE-ACCT / 9600-WRITE-PROCESSING /
 *           9700-CHECK-CHANGE-IN-REC — REWRITE with @Version optimistic lock)</td></tr>
 * </table>
 *
 * <h3>Transactional Semantics</h3>
 * <p>This class is intentionally <strong>not</strong> annotated with
 * {@code @Transactional}. Transaction boundaries are owned by the concrete
 * services:</p>
 * <ul>
 *   <li>{@code AccountViewService} — class-level {@code @Transactional(readOnly = true)}
 *       — applies to {@code getAccountView} (three-way cross-reference/account/customer
 *       read join).</li>
 *   <li>{@code AccountUpdateService} — per-method transactional configuration:
 *       {@code getAccount} inherits the container default for pre-update reads,
 *       {@code updateAccount} runs under full
 *       {@code @Transactional(rollbackFor = Exception.class)} to mirror the COBOL
 *       EXEC CICS SYNCPOINT ROLLBACK semantics for atomic dual-dataset
 *       (ACCTDAT + CUSTDAT) persistence.</li>
 * </ul>
 *
 * <h3>AAP Rule Compliance</h3>
 * <ul>
 *   <li><strong>R-001 — No business logic rewriting</strong> — This class authors
 *       no business logic; every method is a single-line delegation to the
 *       concrete service that owns the real implementation.</li>
 *   <li><strong>R-004 — COBOL traceability preserved</strong> — The routing table
 *       above documents the COBOL program mapping for each delegated method;
 *       the detailed paragraph mappings remain in the concrete service Javadoc.</li>
 *   <li><strong>R-006 — API contract stability</strong> — All method signatures
 *       exactly match the {@link AccountService} interface so that
 *       {@code AccountController} injection and its REST endpoints
 *       ({@code GET /api/accounts/{id}}, {@code PUT /api/accounts/{id}}) remain
 *       behaviorally identical to their pre-refactor semantics.</li>
 *   <li><strong>AAP §0.5.1</strong> — Completes the service-interface refactoring
 *       prescribed in AAP §0.5.1 (File-by-File Transformation Plan) which
 *       specifies that {@code AccountUpdateService} must implement
 *       {@code AccountService} and that the multi-implementation interface
 *       requires an aggregator for controller injection.</li>
 *   <li><strong>AAP §0.7.3</strong> — Resolves the AccountService bean ambiguity
 *       (two concrete {@code @Service} beans implementing the same interface)
 *       via the {@code @Primary} aggregator pattern.</li>
 * </ul>
 *
 * @see AccountService
 * @see AccountViewService
 * @see AccountUpdateService
 */
@Service
@Primary
public class AccountServiceImpl implements AccountService {

    /**
     * Concrete service owning the real implementation of the read-only account
     * view operation ({@link #getAccountView(String)}). Maps to COBOL program
     * COACTVWC.cbl (CICS transaction CAVW) which joins the card cross-reference,
     * account, and customer datasets to produce a composite display record.
     */
    private final AccountViewService accountViewService;

    /**
     * Concrete service owning the real implementation of account write
     * operations ({@link #getAccount(String)} pre-update read and
     * {@link #updateAccount(String, AccountDto)} atomic dual-dataset REWRITE).
     * Maps to COBOL program COACTUPC.cbl (CICS transaction CAUP).
     */
    private final AccountUpdateService accountUpdateService;

    /**
     * Constructs a new {@code AccountServiceImpl} aggregator with the two
     * concrete account-domain service dependencies.
     *
     * <p>Concrete types (not the {@code AccountService} interface) are injected
     * here to eliminate any ambiguity — Spring can resolve each concrete type
     * uniquely because each is a distinct {@code @Service} bean. The interface
     * ambiguity exists only when consumers inject the {@code AccountService}
     * interface type, which is exactly what this aggregator resolves.</p>
     *
     * @param accountViewService   account-view read service (COACTVWC.cbl)
     * @param accountUpdateService account-update write service (COACTUPC.cbl)
     */
    public AccountServiceImpl(AccountViewService accountViewService,
                              AccountUpdateService accountUpdateService) {
        this.accountViewService = accountViewService;
        this.accountUpdateService = accountUpdateService;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Delegates to {@link AccountViewService#getAccountView(String)} — the real
     * implementation mapping COBOL COACTVWC.cbl paragraph 9000-READ-ACCT, which
     * performs the three-way CXACAIX/ACCTDAT/CUSTDAT read-join and returns the
     * composite display DTO.</p>
     */
    @Override
    public AccountDto getAccountView(String acctId) {
        return accountViewService.getAccountView(acctId);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Delegates to {@link AccountUpdateService#getAccount(String)} — the real
     * implementation mapping COBOL COACTUPC.cbl paragraph 9000-READ-ACCT
     * (pre-update read-for-update that populates the account-update DTO with
     * current entity state and the JPA {@code @Version} optimistic-locking
     * token).</p>
     */
    @Override
    public AccountDto getAccount(String acctId) {
        return accountUpdateService.getAccount(acctId);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Delegates to {@link AccountUpdateService#updateAccount(String, AccountDto)}
     * — the real implementation mapping COBOL COACTUPC.cbl paragraphs
     * PROCESS-UPDATE-ACCT / 9600-WRITE-PROCESSING / 9700-CHECK-CHANGE-IN-REC
     * (atomic dual-dataset REWRITE of ACCTDAT and CUSTDAT with JPA
     * {@code @Version} optimistic-locking check, all under method-level
     * {@code @Transactional(rollbackFor = Exception.class)} that mirrors the
     * COBOL EXEC CICS SYNCPOINT ROLLBACK semantics).</p>
     */
    @Override
    public AccountDto updateAccount(String acctId, AccountDto updatedData) {
        return accountUpdateService.updateAccount(acctId, updatedData);
    }
}
