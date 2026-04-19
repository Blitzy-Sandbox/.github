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
package com.cardemo.service.card;

import java.util.List;

import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;

import com.cardemo.model.dto.CardDto;
import com.cardemo.service.interfaces.CardService;

/**
 * Composite aggregator bean that resolves the bean ambiguity created by the
 * three concrete {@link CardService} implementations in the
 * {@code com.cardemo.service.card} package:
 * {@link CardDetailService} (COBOL COCRDSLC.cbl — CCDL transaction),
 * {@link CardListService} (COBOL COCRDLIC.cbl — CCLI transaction), and
 * {@link CardUpdateService} (COBOL COCRDUPC.cbl — CCUP transaction).
 *
 * <p>Each concrete {@code Card*Service} class implements the full {@code CardService}
 * interface contract but provides real implementations for only the method subset
 * that maps to its COBOL program of origin; all other interface methods are
 * intentionally stubbed to throw {@link UnsupportedOperationException} (the
 * "Interface-Contract Stubs" pattern). Without a disambiguator, Spring cannot
 * autowire a {@code CardService} bean into {@code CardController} because three
 * candidate beans satisfy the interface type.</p>
 *
 * <p>{@code CardServiceImpl} is marked {@link Primary @Primary} so that Spring
 * selects it as the default {@code CardService} injection target, and it delegates
 * each interface method to the concrete service that owns the real implementation
 * for that method. This mirrors the established aggregator pattern already used
 * in this codebase by
 * {@link com.cardemo.service.admin.AdminServiceImpl AdminServiceImpl},
 * {@link com.cardemo.service.menu.MenuServiceImpl MenuServiceImpl}, and
 * {@link com.cardemo.service.transaction.TransactionServiceImpl TransactionServiceImpl}.</p>
 *
 * <h3>Method Routing Table</h3>
 * <table>
 *   <caption>Maps each {@code CardService} method to the concrete bean owning the real implementation</caption>
 *   <tr><th>Interface Method</th><th>Routed To</th><th>COBOL Program</th></tr>
 *   <tr><td>{@link #getCardDetail(String)}</td>
 *       <td>{@code CardDetailService}</td>
 *       <td>COCRDSLC.cbl (paragraph 9000-READCARD-AID)</td></tr>
 *   <tr><td>{@link #getCardsByAccountId(String)}</td>
 *       <td>{@code CardDetailService}</td>
 *       <td>COCRDSLC.cbl (paragraph 9150-GETCARD-BYACCT — CXACAIX alternate index)</td></tr>
 *   <tr><td>{@link #listCards(int, String, String)}</td>
 *       <td>{@code CardListService}</td>
 *       <td>COCRDLIC.cbl (paragraphs 9000-STARTBR-CARDAT / 9100-GETNEXT-CARDAT)</td></tr>
 *   <tr><td>{@link #listCardsByAccount(String, int)}</td>
 *       <td>{@code CardListService}</td>
 *       <td>COCRDLIC.cbl (account-scoped browse)</td></tr>
 *   <tr><td>{@link #getCardForUpdate(String)}</td>
 *       <td>{@code CardUpdateService}</td>
 *       <td>COCRDUPC.cbl (paragraph 9000-READ-CARD — rendered read)</td></tr>
 *   <tr><td>{@link #updateCard(String, CardDto)}</td>
 *       <td>{@code CardUpdateService}</td>
 *       <td>COCRDUPC.cbl (paragraph 9200-WRITE-PROCESSING — REWRITE with @Version optimistic lock)</td></tr>
 * </table>
 *
 * <h3>Transactional Semantics</h3>
 * <p>This class is intentionally <strong>not</strong> annotated with
 * {@code @Transactional}. Transaction boundaries are owned by the concrete
 * services:</p>
 * <ul>
 *   <li>{@code CardDetailService} — class-level {@code @Transactional(readOnly = true)}
 *       — applies to {@code getCardDetail} and {@code getCardsByAccountId}.</li>
 *   <li>{@code CardListService} — class-level {@code @Transactional(readOnly = true)}
 *       — applies to {@code listCards} and {@code listCardsByAccount}.</li>
 *   <li>{@code CardUpdateService} — per-method transactional configuration: read-only
 *       for {@code getCardForUpdate}, full transactional with {@code rollbackFor = Exception.class}
 *       for {@code updateCard} (mirrors COBOL SYNCPOINT ROLLBACK semantics).</li>
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
 *   <li><strong>R-006 — API contract stability</strong> — All method signatures,
 *       including the typed generic {@code Page<CardDto>} and {@code List<CardDto>}
 *       return types, exactly match the {@link CardService} interface. The
 *       {@code List<CardDto>} return type for {@link #getCardsByAccountId(String)}
 *       is preserved per AAP §0.7.3 because the COBOL CXACAIX alternate index is
 *       a NONUNIQUEKEY index that typically returns 1–5 rows per account —
 *       pagination would be inappropriate.</li>
 *   <li><strong>AAP §0.7.3</strong> — Resolves the CardService bean ambiguity
 *       (three concrete {@code @Service} beans implementing the same interface)
 *       via the {@code @Primary} aggregator pattern.</li>
 * </ul>
 *
 * @see CardService
 * @see CardDetailService
 * @see CardListService
 * @see CardUpdateService
 */
@Service
@Primary
public class CardServiceImpl implements CardService {

    /**
     * Concrete service owning the real implementation of card-detail read
     * operations ({@link #getCardDetail(String)} and {@link #getCardsByAccountId(String)}).
     * Maps to COBOL program COCRDSLC.cbl (CICS transaction CCDL).
     */
    private final CardDetailService cardDetailService;

    /**
     * Concrete service owning the real implementation of card-list browse
     * operations ({@link #listCards(int, String, String)} and
     * {@link #listCardsByAccount(String, int)}).
     * Maps to COBOL program COCRDLIC.cbl (CICS transaction CCLI).
     */
    private final CardListService cardListService;

    /**
     * Concrete service owning the real implementation of card-update write
     * operations ({@link #getCardForUpdate(String)} and
     * {@link #updateCard(String, CardDto)}).
     * Maps to COBOL program COCRDUPC.cbl (CICS transaction CCUP).
     */
    private final CardUpdateService cardUpdateService;

    /**
     * Constructs a new {@code CardServiceImpl} aggregator with the three concrete
     * card-domain service dependencies.
     *
     * <p>Concrete types (not the {@code CardService} interface) are injected here
     * to eliminate any ambiguity — Spring can resolve each concrete type uniquely
     * because each is a distinct {@code @Service} bean. The interface ambiguity
     * exists only when consumers inject the {@code CardService} interface type,
     * which is exactly what this aggregator resolves.</p>
     *
     * @param cardDetailService card-detail read service (COCRDSLC.cbl)
     * @param cardListService   card-list browse service (COCRDLIC.cbl)
     * @param cardUpdateService card-update write service (COCRDUPC.cbl)
     */
    public CardServiceImpl(CardDetailService cardDetailService,
                           CardListService cardListService,
                           CardUpdateService cardUpdateService) {
        this.cardDetailService = cardDetailService;
        this.cardListService = cardListService;
        this.cardUpdateService = cardUpdateService;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Delegates to {@link CardDetailService#getCardDetail(String)} — the real
     * implementation mapping COBOL COCRDSLC.cbl paragraph 9000-READCARD-AID.</p>
     */
    @Override
    public CardDto getCardDetail(String cardNum) {
        return cardDetailService.getCardDetail(cardNum);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Delegates to {@link CardDetailService#getCardsByAccountId(String)} — the real
     * implementation mapping COBOL COCRDSLC.cbl paragraph 9150-GETCARD-BYACCT
     * (CXACAIX NONUNIQUEKEY alternate index read).</p>
     */
    @Override
    public List<CardDto> getCardsByAccountId(String acctId) {
        return cardDetailService.getCardsByAccountId(acctId);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Delegates to {@link CardListService#listCards(int, String, String)} — the real
     * implementation mapping COBOL COCRDLIC.cbl paragraphs 9000-STARTBR-CARDAT /
     * 9100-GETNEXT-CARDAT (CARDDAT KSDS sequential browse with filter).</p>
     */
    @Override
    public Page<CardDto> listCards(int page, String acctIdFilter, String cardNumFilter) {
        return cardListService.listCards(page, acctIdFilter, cardNumFilter);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Delegates to {@link CardListService#listCardsByAccount(String, int)} — the real
     * implementation mapping COBOL COCRDLIC.cbl (account-scoped card browse via
     * CXACAIX alternate index).</p>
     */
    @Override
    public Page<CardDto> listCardsByAccount(String acctId, int page) {
        return cardListService.listCardsByAccount(acctId, page);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Delegates to {@link CardUpdateService#getCardForUpdate(String)} — the real
     * implementation mapping COBOL COCRDUPC.cbl paragraph 9000-READ-CARD (rendered
     * read before update).</p>
     */
    @Override
    public CardDto getCardForUpdate(String cardNum) {
        return cardUpdateService.getCardForUpdate(cardNum);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Delegates to {@link CardUpdateService#updateCard(String, CardDto)} — the real
     * implementation mapping COBOL COCRDUPC.cbl paragraph 9200-WRITE-PROCESSING
     * (REWRITE with JPA {@code @Version} optimistic lock mirroring SYNCPOINT
     * ROLLBACK on concurrent modification).</p>
     */
    @Override
    public CardDto updateCard(String cardNum, CardDto updateRequest) {
        return cardUpdateService.updateCard(cardNum, updateRequest);
    }
}
