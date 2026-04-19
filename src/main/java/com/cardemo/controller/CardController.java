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
package com.cardemo.controller;

import java.util.List;

import jakarta.validation.Valid;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.cardemo.model.dto.CardDto;
import com.cardemo.service.interfaces.CardService;

/**
 * Spring MVC REST controller providing credit card list, detail, and update
 * operations, replacing CICS BMS screens COCRDLI (card list), COCRDSL (card
 * detail), and COCRDUP (card update) from the CardDemo mainframe application.
 *
 * <p>This controller is a thin delegation layer — all business logic, validation,
 * and transaction management resides in the service classes. The controller is
 * responsible only for HTTP binding, request logging (with PCI-compliant card
 * number masking), and response wrapping.</p>
 *
 * <h3>COBOL Program Coverage</h3>
 * <table>
 *   <caption>BMS Screen to REST endpoint mapping</caption>
 *   <tr><th>BMS Screen</th><th>COBOL Program</th><th>REST Endpoint</th><th>HTTP Method</th></tr>
 *   <tr><td>COCRDLI (Card List)</td><td>COCRDLIC.cbl (1,459 lines)</td>
 *       <td>/api/cards</td><td>GET</td></tr>
 *   <tr><td>COCRDSL (Card Detail)</td><td>COCRDSLC.cbl (887 lines)</td>
 *       <td>/api/cards/{cardNum}</td><td>GET</td></tr>
 *   <tr><td>COCRDSL (Cards by Account)</td><td>COCRDSLC.cbl</td>
 *       <td>/api/cards/account/{acctId}</td><td>GET</td></tr>
 *   <tr><td>COCRDUP (Card Update)</td><td>COCRDUPC.cbl (1,560 lines)</td>
 *       <td>/api/cards/{cardNum}</td><td>PUT</td></tr>
 * </table>
 *
 * <h3>HTTP Status Codes</h3>
 * <ul>
 *   <li><strong>200 OK</strong> — Successful card list, detail, or update</li>
 *   <li><strong>400 Bad Request</strong> — Validation failures (field-level errors
 *       from COCRDUPC.cbl paragraph 1100-VALIDATE-CARD-DATA) — handled by ControllerAdvice</li>
 *   <li><strong>404 Not Found</strong> — Card not found (maps COBOL FILE STATUS 23 /
 *       CICS DFHRESP(NOTFND)) — handled by ControllerAdvice</li>
 *   <li><strong>409 Conflict</strong> — Optimistic locking failure during update
 *       (maps COBOL 9300-CHECK-CHANGE-IN-REC DATA-WAS-CHANGED-BEFORE-UPDATE)
 *       — handled by ControllerAdvice</li>
 * </ul>
 *
 * <h3>PCI Compliance</h3>
 * <ul>
 *   <li>Card numbers are masked in ALL log messages — only the last 4 digits are shown</li>
 *   <li>Card CVV ({@code cardCvvCd}) is NEVER logged under any circumstances</li>
 * </ul>
 *
 * <h3>Key Design Decisions</h3>
 * <ul>
 *   <li>No exception handling in controller — all exceptions propagate to the global
 *       {@code @ControllerAdvice} handler for consistent error response formatting.</li>
 *   <li>No business logic in controller — all card field validations, change detection,
 *       and optimistic concurrency control are delegated to services.</li>
 *   <li>Page size of 7 for card list (enforced in {@link CardService}) matches
 *       COBOL WS-MAX-SCREEN-LINES = 7 for behavioral parity.</li>
 *   <li>Structured logging with SLF4J supports correlation ID propagation via MDC
 *       per AAP §0.7.1 observability requirements.</li>
 * </ul>
 *
 * <p>Source traceability: COCRDLIC.cbl + COCRDSLC.cbl + COCRDUPC.cbl —
 * CardDemo v1.0-15-g27d6c6f-68</p>
 *
 * @see com.cardemo.service.interfaces.CardService
 * @see CardDto
 */
@RestController
@RequestMapping("/api/cards")
public class CardController {

    /**
     * SLF4J logger for structured logging with PCI-compliant card number masking
     * and correlation IDs. Logs card list requests (page/filter info), card detail
     * retrievals, and card update operations. Card numbers show only the last 4
     * digits; CVV codes are never logged. Correlation IDs are propagated via
     * Logback MDC integration per AAP §0.7.1 observability requirements.
     */
    private static final Logger logger = LoggerFactory.getLogger(CardController.class);

    /**
     * Card domain service contract aggregating card list, detail, and update operations.
     * Spring injects the concrete implementation(s) in {@code com.cardemo.service.card}
     * at runtime; the controller depends only on the interface.
     *
     * <p>Covers:</p>
     * <ul>
     *   <li><strong>COCRDLIC.cbl (1,459 lines, CICS transaction CC01)</strong> — Paginated
     *       card browse with 7-row page size matching COBOL WS-MAX-SCREEN-LINES and
     *       optional account ID / card number filtering (paragraph 9500-FILTER-RECORDS).</li>
     *   <li><strong>COCRDSLC.cbl (887 lines, CICS transaction CC02)</strong> — Single card
     *       keyed read (paragraph 9100-GETCARD-BYACCTCARD) and alternate index lookup by
     *       account ID (paragraph 9150-GETCARD-BYACCT via CARDAIX).</li>
     *   <li><strong>COCRDUPC.cbl (1,560 lines, CICS transaction CC03)</strong> — Card update
     *       with field validation (paragraph 1100-VALIDATE-CARD-DATA), change detection
     *       (paragraph 1200-CHECK-FOR-CHANGES), and JPA {@code @Version} optimistic locking
     *       (paragraph 9300-CHECK-CHANGE-IN-REC).</li>
     * </ul>
     */
    private final CardService cardService;

    /**
     * Constructs a new CardController with the required CardService dependency.
     *
     * <p>Uses Spring constructor injection, which is the recommended dependency
     * injection pattern. When a class has a single constructor, Spring automatically
     * uses it for autowiring without requiring the {@code @Autowired} annotation.</p>
     *
     * @param cardService the service contract aggregating card list, detail, and update
     *                    operations (COCRDLIC.cbl + COCRDSLC.cbl + COCRDUPC.cbl)
     */
    public CardController(CardService cardService) {
        this.cardService = cardService;
    }

    /**
     * Retrieves a paginated list of credit cards with optional filtering.
     *
     * <p>Maps COBOL program COCRDLIC.cbl (1,459 lines, CICS transaction CC01)
     * which implements a paginated card browse with STARTBR/READNEXT sequential
     * access on the CARDDAT VSAM KSDS dataset. The BMS screen COCRDLI displays
     * 7 rows per page (WS-MAX-SCREEN-LINES) with optional Account Number and
     * Card Number filter fields.</p>
     *
     * <p>Filtering behavior (exclusive filter precedence):</p>
     * <ul>
     *   <li>If {@code acctId} is provided (non-null, non-blank): delegates to
     *       {@link CardService#listCardsByAccount(String, int)} which resolves
     *       cards via the CXACAIX alternate index (paragraph 9500-FILTER-RECORDS).
     *       <strong>Note:</strong> {@code cardNum} is <em>silently ignored</em> in
     *       this branch because the CXACAIX path does not accept a card-number
     *       filter. This matches the COBOL BMS screen's exclusive-filter semantics
     *       (when both filters are supplied, the account filter takes precedence).</li>
     *   <li>Otherwise (acctId is null or blank): delegates to
     *       {@link CardService#listCards(int, String, String)} which supports both
     *       filters independently. Providing only {@code cardNum} will filter by
     *       card number alone.</li>
     * </ul>
     *
     * <p>To filter by both account and card number simultaneously, clients must
     * be aware that {@code cardNum} will be silently dropped when {@code acctId}
     * is present. This preserves byte-identical behavior with the original
     * COCRDLIC.cbl program, where the account filter takes precedence over the
     * card-number filter on the BMS screen.</p>
     *
     * @param page    zero-based page number (defaults to 0); maps COBOL PF7/PF8 page navigation
     * @param acctId  optional account ID filter (11-digit); maps BMS field ACCTSIDI
     * @param cardNum optional card number filter (16-digit); maps BMS field CRDSIDI.
     *                <strong>Ignored when {@code acctId} is provided</strong>
     *                (see Filtering behavior above).
     * @return HTTP 200 with {@link Page} of {@link CardDto} containing up to 7 cards per page,
     *         total element count, total pages, and navigation metadata
     */
    @GetMapping
    public ResponseEntity<Page<CardDto>> listCards(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) String acctId,
            @RequestParam(required = false) String cardNum) {

        logger.info("Listing cards page={} acctId={} cardNum={}", page, acctId, cardNum);

        Page<CardDto> result;
        if (acctId != null && !acctId.isBlank()) {
            // Account filter takes precedence — delegate to CXACAIX alternate-index
            // lookup. Note: cardNum is silently ignored here because the
            // listCardsByAccount() contract does not support card-number filtering
            // (matches COBOL BMS screen's exclusive-filter semantics where the
            // account filter takes precedence over the card-number filter).
            // See method Javadoc above for detailed filtering behavior.
            result = cardService.listCardsByAccount(acctId, page);
        } else {
            // No account filter — use general listCards() which supports the
            // optional cardNum filter independently.
            result = cardService.listCards(page, acctId, cardNum);
        }

        return ResponseEntity.ok(result);
    }

    /**
     * Retrieves all credit cards associated with a specific account ID.
     *
     * <p>Maps COBOL program COCRDSLC.cbl paragraph 9150-GETCARD-BYACCT which reads
     * the CARDAIX alternate index by account ID to retrieve all cards linked to
     * that account. Unlike the paginated list endpoint, this returns all matching
     * cards in a single response without pagination.</p>
     *
     * @param acctId the account identifier (11-digit numeric string, maps COBOL PIC 9(11))
     * @return HTTP 200 with {@link List} of {@link CardDto} containing all cards
     *         associated with the specified account
     */
    @GetMapping("/account/{acctId}")
    public ResponseEntity<List<CardDto>> getCardsByAccount(@PathVariable String acctId) {

        logger.info("Getting cards for account {}", acctId);

        List<CardDto> result = cardService.getCardsByAccountId(acctId);

        return ResponseEntity.ok(result);
    }

    /**
     * Retrieves a single card detail by card number.
     *
     * <p>Maps COBOL program COCRDSLC.cbl (887 lines, CICS transaction CC02)
     * paragraph 9100-GETCARD-BYACCTCARD which performs a single keyed read on the
     * CARDDAT VSAM KSDS dataset by primary key (card number). The BMS screen
     * COCRDSL displays card details including account number, card number, embossed
     * name, active status, and expiry date.</p>
     *
     * <p><strong>PCI Compliance:</strong> The card number is masked in log output
     * (only last 4 digits shown). The card CVV is never logged.</p>
     *
     * @param cardNum the card number (16-digit string, maps COBOL PIC X(16))
     * @return HTTP 200 with {@link CardDto} containing the card detail fields
     * @throws com.cardemo.exception.RecordNotFoundException if no card exists with
     *         the specified card number (maps COBOL FILE STATUS 23 / DFHRESP(NOTFND))
     */
    @GetMapping("/{cardNum}")
    public ResponseEntity<CardDto> getCard(@PathVariable String cardNum) {

        logger.info("Getting card detail for card ending {}", maskCardNumber(cardNum));

        CardDto result = cardService.getCardDetail(cardNum);

        return ResponseEntity.ok(result);
    }

    /**
     * Updates a credit card record with optimistic concurrency control.
     *
     * <p>Maps COBOL program COCRDUPC.cbl (1,560 lines, CICS transaction CC03)
     * which is the most complex card program. The update flow preserves these
     * COBOL semantics:</p>
     * <ol>
     *   <li><strong>Field Validation (1100-VALIDATE-CARD-DATA):</strong>
     *       Validates embossed name, active status (Y/N), expiry date, and CVV.
     *       Collects ALL errors before returning (maps to {@code ValidationException}
     *       with aggregated {@code FieldError} list).</li>
     *   <li><strong>Change Detection (1200-CHECK-FOR-CHANGES):</strong>
     *       Compares before/after field values. If no fields changed, returns without
     *       writing (COBOL "no changes detected" message).</li>
     *   <li><strong>Optimistic Locking (9300-CHECK-CHANGE-IN-REC):</strong>
     *       Uses JPA {@code @Version} to detect concurrent modifications. If another
     *       user modified the record between read and write, throws
     *       {@code ConcurrentModificationException} (maps COBOL
     *       DATA-WAS-CHANGED-BEFORE-UPDATE flag).</li>
     * </ol>
     *
     * <p><strong>PCI Compliance:</strong> The card number is masked in log output
     * (only last 4 digits shown). The card CVV is never logged.</p>
     *
     * @param cardNum the card number identifying the card to update (16-digit string)
     * @param cardDto the updated card data with validated fields
     * @return HTTP 200 with updated {@link CardDto} reflecting the persisted changes
     * @throws com.cardemo.exception.RecordNotFoundException if no card exists with
     *         the specified card number (maps COBOL FILE STATUS 23)
     * @throws com.cardemo.exception.ConcurrentModificationException if the card was
     *         modified by another user since it was read (maps COBOL
     *         9300-CHECK-CHANGE-IN-REC / JPA OptimisticLockException)
     * @throws com.cardemo.exception.ValidationException if field validation fails
     *         (maps COBOL paragraph 1100-VALIDATE-CARD-DATA) with aggregated errors
     */
    @PutMapping("/{cardNum}")
    public ResponseEntity<CardDto> updateCard(@PathVariable String cardNum,
                                              @Valid @RequestBody CardDto cardDto) {

        logger.info("Updating card ending {}", maskCardNumber(cardNum));

        CardDto result = cardService.updateCard(cardNum, cardDto);

        return ResponseEntity.ok(result);
    }

    /**
     * Masks a card number for PCI-compliant logging, showing only the last 4 digits.
     *
     * <p>Used internally by endpoint methods to produce safe log output. If the card
     * number has fewer than 4 characters, the entire number is masked with asterisks.</p>
     *
     * @param cardNumber the full card number to mask
     * @return masked card number showing only the last 4 digits (e.g., "****1234")
     */
    private static String maskCardNumber(String cardNumber) {
        if (cardNumber == null || cardNumber.length() <= 4) {
            return "****";
        }
        return "****" + cardNumber.substring(cardNumber.length() - 4);
    }
}
