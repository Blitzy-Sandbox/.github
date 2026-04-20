package com.cardemo.service.card;

import com.cardemo.domain.constants.CardConstants;
import com.cardemo.domain.validation.CardValidator;
import com.cardemo.exception.ConcurrentModificationException;
import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.CardDto;
import com.cardemo.model.entity.Account;
import com.cardemo.model.entity.Card;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CardRepository;
import com.cardemo.service.interfaces.CardService;

import jakarta.persistence.OptimisticLockException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * Service for card update operations — migrated from COCRDUPC.cbl (1,560 lines).
 *
 * <p>Implements optimistic concurrency via JPA {@code @Version} on the {@link Card} entity,
 * replacing the COBOL CICS READ UPDATE + snapshot comparison pattern (paragraph
 * 9300-CHECK-CHANGE-IN-REC). Handles card status, expiration date, CVV, and embossed
 * name updates with field-by-field validation that collects all errors before returning
 * (matching COBOL behavior of marking all invalid fields rather than short-circuiting).</p>
 *
 * <p>Key COBOL paragraph mappings:</p>
 * <ul>
 *   <li>9000-READ-DATA / 9100-GETCARD-BYACCTCARD → {@link #getCardForUpdate(String)}</li>
 *   <li>1200-EDIT-MAP-INPUTS / 1230–1260 → {@link CardValidator#validateFields(CardDto)}</li>
 *   <li>1200-CHECK-FOR-CHANGES → {@link #hasChanges(Card, CardDto)}</li>
 *   <li>9200-WRITE-PROCESSING → {@link #updateCard(String, CardDto)} save step</li>
 *   <li>9300-CHECK-CHANGE-IN-REC → JPA {@code @Version} optimistic locking</li>
 * </ul>
 *
 * @see Card
 * @see CardRepository
 */
@Service
public class CardUpdateService implements CardService {

    private static final Logger logger = LoggerFactory.getLogger(CardUpdateService.class);

    private final CardRepository cardRepository;
    private final AccountRepository accountRepository;
    private final CardValidator cardValidator;

    /**
     * Constructs a CardUpdateService with required repository dependencies.
     *
     * @param cardRepository    repository for Card entity CRUD operations
     * @param accountRepository repository for Account entity lookups (9100-READ-ACCT)
     * @param cardValidator     domain validator encapsulating COBOL 1200-EDIT-MAP-INPUTS
     *                          field validation (paragraphs 1210–1260). Delegated to from
     *                          {@link #updateCard(String, CardDto)}.
     */
    public CardUpdateService(CardRepository cardRepository,
                             AccountRepository accountRepository,
                             CardValidator cardValidator) {
        this.cardRepository = cardRepository;
        this.accountRepository = accountRepository;
        this.cardValidator = cardValidator;
    }

    /**
     * Retrieves a card record for editing — maps COBOL paragraph 9000-READ-DATA /
     * 9100-GETCARD-BYACCTCARD.
     *
     * <p>Performs a read-only transactional lookup of the card by its primary key (card number).
     * Maps to {@code EXEC CICS READ FILE(CARDFILENAME) RIDFLD(WS-CARD-RID-CARDNUM)}.</p>
     *
     * @param cardNum the 16-digit card number (primary key)
     * @return {@link CardDto} populated with current card data including version for
     *         optimistic locking on subsequent update
     * @throws RecordNotFoundException if no card exists with the given number (FILE STATUS 23)
     */
    @Override
    @Transactional(readOnly = true)
    public CardDto getCardForUpdate(String cardNum) {
        if (cardNum == null || cardNum.isBlank()) {
            throw new RecordNotFoundException("Card", cardNum);
        }

        logger.info("Retrieving card ending in {} for update", maskCardNumber(cardNum));

        Card card = cardRepository.findById(cardNum)
                .orElseThrow(() -> {
                    logger.warn("Card not found: ending in {}", maskCardNumber(cardNum));
                    return new RecordNotFoundException("Card", cardNum);
                });

        // Verify associated account still exists (informational integrity check)
        if (card.getCardAcctId() != null && !card.getCardAcctId().isBlank()) {
            boolean acctExists = accountRepository.existsById(card.getCardAcctId());
            if (!acctExists) {
                logger.warn("Card's associated account ending in {} not found",
                        maskAccountId(card.getCardAcctId()));
            }
        }

        logger.debug("Retrieved card ending in {} with version {}",
                maskCardNumber(card.getCardNum()), card.getVersion());
        return toCardDto(card);
    }

    /**
     * Validates and saves card updates — maps COBOL paragraphs 1100-VALIDATE-CARD-DATA,
     * 1200-CHECK-FOR-CHANGES, 9200-WRITE-PROCESSING, and 9300-CHECK-CHANGE-IN-REC.
     *
     * <p>Processing steps (matching COBOL state machine flow):</p>
     * <ol>
     *   <li>Fetch current card record (9100-GETCARD-BYACCTCARD)</li>
     *   <li>Field-by-field validation collecting ALL errors (1100-VALIDATE-CARD-DATA)</li>
     *   <li>Verify associated account exists (9100-READ-ACCT)</li>
     *   <li>Change detection with upper-case comparison (1200-CHECK-FOR-CHANGES)</li>
     *   <li>Apply field changes — cardNum and cardAcctId are NEVER modified (key fields)</li>
     *   <li>Save with JPA @Version optimistic locking (9200-WRITE-CARD / 9300-CHECK-CHANGE-IN-REC)</li>
     * </ol>
     *
     * @param cardNum       the card number identifying the card to update
     * @param updateRequest DTO containing the updated field values
     * @return {@link CardDto} with the saved card data
     * @throws RecordNotFoundException          if card or associated account not found
     * @throws ValidationException              if one or more field validations fail
     * @throws ConcurrentModificationException  if another user modified the record concurrently
     */
    @Override
    @Transactional
    public CardDto updateCard(String cardNum, CardDto updateRequest) {
        logger.info("Updating card ending in {}", maskCardNumber(cardNum));

        // Step 1: Fetch current record (maps 9000-READ-DATA / 9100-GETCARD-BYACCTCARD)
        Card card = cardRepository.findById(cardNum)
                .orElseThrow(() -> {
                    logger.warn("Card not found for update: ending in {}", maskCardNumber(cardNum));
                    return new RecordNotFoundException("Card", cardNum);
                });

        // Step 1.5: Optimistic concurrency control — compare client-supplied version with DB version
        // Maps to COCRDUPC.cbl paragraph 9300-CHECK-CHANGE-IN-REC: if the client-supplied version
        // does not match the current DB version, the record was modified by another user since the
        // client's last read. Reject with ConcurrentModificationException (HTTP 409 Conflict).
        if (updateRequest.getVersion() != null
                && !updateRequest.getVersion().equals(card.getVersion())) {
            logger.error("Optimistic lock conflict: client version={}, DB version={} for card ending in {}",
                    updateRequest.getVersion(), card.getVersion(), maskCardNumber(cardNum));
            throw new ConcurrentModificationException(
                    "Card record was modified by another user. "
                            + "Expected version " + updateRequest.getVersion()
                            + " but found version " + card.getVersion());
        }

        // Step 2: Field-by-field validation (maps 1100-VALIDATE-CARD-DATA paragraphs 1210–1260)
        // Collects ALL errors before throwing — matches COBOL behavior of marking all bad fields.
        // Delegated to domain validator — see CardValidator.validateFields for paragraph mappings.
        cardValidator.validateFields(updateRequest);

        // Step 3: Verify associated account exists (maps 9100-READ-ACCT)
        verifyAccountExists(updateRequest.getCardAcctId());

        // Step 4: Change detection (maps 1200-CHECK-FOR-CHANGES with FUNCTION UPPER-CASE)
        if (!hasChanges(card, updateRequest)) {
            logger.info("No changes detected for card ending in {}", maskCardNumber(cardNum));
            return toCardDto(card);
        }

        // Step 5: Apply changes — cardNum and cardAcctId are NEVER updated (key fields per COBOL)
        card.setCardCvvCd(updateRequest.getCardCvvCd());
        card.setCardEmbossedName(updateRequest.getCardEmbossedName());
        card.setCardExpDate(updateRequest.getCardExpDate());
        card.setCardActiveStatus(updateRequest.getCardActiveStatus());

        // Step 6: Save with optimistic locking (maps 9200-WRITE-CARD / 9300-CHECK-CHANGE-IN-REC)
        // JPA @Version on Card entity triggers OptimisticLockException on version mismatch.
        // saveAndFlush() ensures the JPA @Version field is incremented immediately so the
        // returned entity reflects the post-save version number in the PUT response.
        try {
            Card savedCard = cardRepository.saveAndFlush(card);
            logger.info("Successfully updated card ending in {}", maskCardNumber(cardNum));
            return toCardDto(savedCard);
        } catch (OptimisticLockException ex) {
            // Direct JPA optimistic lock — preserve cause chain for diagnostics
            logger.warn("Concurrent modification detected for card ending in {}",
                    maskCardNumber(cardNum));
            throw new ConcurrentModificationException(
                    "Card record was modified by another user", ex);
        } catch (RuntimeException ex) {
            // Spring Data wraps JPA exceptions — check cause chain for optimistic lock
            if (isOptimisticLockFailure(ex)) {
                logger.warn("Concurrent modification detected for card ending in {}",
                        maskCardNumber(cardNum));
                throw new ConcurrentModificationException("Card", cardNum);
            }
            throw ex;
        }
    }

    /**
     * Verifies that the account associated with the card exists in the database.
     * Maps COBOL paragraph 9100-READ-ACCT which reads ACCTDAT to confirm the
     * account referenced by the card is valid.
     *
     * @param acctId the account ID to verify
     * @throws RecordNotFoundException if the account does not exist
     */
    private void verifyAccountExists(String acctId) {
        if (acctId == null || acctId.isBlank()) {
            return;
        }
        Account account = accountRepository.findById(acctId)
                .orElseThrow(() -> {
                    logger.warn("Account ending in {} not found for card update",
                            maskAccountId(acctId));
                    return new RecordNotFoundException("Account", acctId);
                });
        logger.debug("Verified account ending in {} exists for card update",
                maskAccountId(account.getAcctId()));
    }

    /**
     * Detects whether any updatable fields have changed between the current database
     * record and the update request. Uses UPPER-CASE comparison for string fields,
     * matching COBOL paragraph 1200-CHECK-FOR-CHANGES: {@code FUNCTION UPPER-CASE(CCUP-NEW-CARDDATA)
     * EQUAL FUNCTION UPPER-CASE(CCUP-OLD-CARDDATA)}.
     *
     * @param currentCard   the current card entity from the database
     * @param updateRequest the incoming update DTO
     * @return {@code true} if any field differs, {@code false} if all fields are identical
     */
    private boolean hasChanges(Card currentCard, CardDto updateRequest) {
        boolean cvvMatch = safeUpperEquals(
                currentCard.getCardCvvCd(), updateRequest.getCardCvvCd());
        boolean nameMatch = safeUpperEquals(
                currentCard.getCardEmbossedName(), updateRequest.getCardEmbossedName());
        boolean statusMatch = safeUpperEquals(
                currentCard.getCardActiveStatus(), updateRequest.getCardActiveStatus());
        boolean dateMatch = safeLocalDateEquals(
                currentCard.getCardExpDate(), updateRequest.getCardExpDate());

        return !(cvvMatch && nameMatch && statusMatch && dateMatch);
    }

    /**
     * Null-safe upper-case string comparison — mirrors COBOL FUNCTION UPPER-CASE behavior.
     */
    private boolean safeUpperEquals(String a, String b) {
        if (a == null && b == null) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return a.toUpperCase().equals(b.toUpperCase());
    }

    /**
     * Null-safe LocalDate comparison for expiration date matching.
     */
    private boolean safeLocalDateEquals(LocalDate a, LocalDate b) {
        if (a == null && b == null) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return a.equals(b);
    }

    /**
     * Checks the exception cause chain for optimistic locking failures.
     * Spring Data JPA wraps JPA {@link OptimisticLockException} in its own exception hierarchy
     * (ObjectOptimisticLockingFailureException). This method traverses the cause chain to
     * detect either the JPA exception or Spring's wrapper.
     */
    private boolean isOptimisticLockFailure(Throwable ex) {
        Throwable cause = ex;
        while (cause != null) {
            if (cause instanceof OptimisticLockException) {
                return true;
            }
            String className = cause.getClass().getSimpleName();
            if (className.contains("OptimisticLocking")) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    /**
     * Converts a Card entity to a CardDto for API responses.
     * Maps all six fields: cardNum, cardAcctId, cardEmbossedName, cardExpDate,
     * cardActiveStatus, cardCvvCd.
     *
     * @param card the Card entity to convert
     * @return populated CardDto
     */
    private CardDto toCardDto(Card card) {
        CardDto dto = new CardDto();
        dto.setCardNum(card.getCardNum());
        dto.setCardAcctId(card.getCardAcctId());
        dto.setCardEmbossedName(card.getCardEmbossedName());
        dto.setCardExpDate(card.getCardExpDate());
        dto.setCardActiveStatus(card.getCardActiveStatus());
        dto.setCardCvvCd(card.getCardCvvCd());
        // Optimistic locking version — exposes JPA @Version for concurrent modification
        // detection via REST API, matching AccountDto pattern (AAP §0.8.4).
        dto.setVersion(card.getVersion());
        return dto;
    }

    /**
     * Masks a card number for safe logging — shows only the last
     * {@link CardConstants#MASK_VISIBLE_DIGITS} digits.
     * Prevents PII exposure in log files per security requirements.
     */
    private String maskCardNumber(String cardNum) {
        if (cardNum == null || cardNum.length() <= CardConstants.MASK_VISIBLE_DIGITS) {
            return "*".repeat(CardConstants.MASK_VISIBLE_DIGITS);
        }
        return "*".repeat(CardConstants.MASK_VISIBLE_DIGITS)
                + cardNum.substring(cardNum.length() - CardConstants.MASK_VISIBLE_DIGITS);
    }

    /**
     * Masks an account ID for safe logging — shows only the last
     * {@link CardConstants#MASK_VISIBLE_DIGITS} digits.
     * Prevents PII exposure in log files per security requirements.
     */
    private String maskAccountId(String acctId) {
        if (acctId == null || acctId.length() <= CardConstants.MASK_VISIBLE_DIGITS) {
            return "*".repeat(CardConstants.MASK_VISIBLE_DIGITS);
        }
        return "*".repeat(CardConstants.MASK_VISIBLE_DIGITS)
                + acctId.substring(acctId.length() - CardConstants.MASK_VISIBLE_DIGITS);
    }

    // -----------------------------------------------------------------------
    // Unsupported Interface Methods — Implemented by Sibling Services
    // -----------------------------------------------------------------------
    //
    // The CardService interface aggregates operations across three concrete
    // @Service classes under com.cardemo.service.card (AAP §0.7.3).
    // CardUpdateService owns only the two update-related methods above
    // (getCardForUpdate, updateCard). The four methods below are declared
    // on the interface but are implemented by the sibling @Service beans
    // (CardDetailService, CardListService) and therefore throw
    // UnsupportedOperationException when invoked on this instance. This
    // pattern matches the one used by CardDetailService and CardListService
    // and the AdminService / MenuService / TransactionService aggregated
    // interfaces per the folder-level AAP Section 0.5.1
    // ("Interface-Contract Stubs" pattern).

    /**
     * Not supported by {@code CardUpdateService}. Implemented by
     * {@code com.cardemo.service.card.CardDetailService#getCardDetail(String)}.
     *
     * <p>Consumers that need the single-card primary-key lookup operation
     * should inject the {@code CardDetailService} bean directly (or the
     * {@link CardService} interface qualified to that bean) rather than
     * this update service instance.</p>
     *
     * @param cardNum ignored
     * @return never returns normally
     * @throws UnsupportedOperationException always
     */
    @Override
    public CardDto getCardDetail(String cardNum) {
        throw new UnsupportedOperationException(
                "getCardDetail is implemented by CardDetailService, not CardUpdateService");
    }

    /**
     * Not supported by {@code CardUpdateService}. Implemented by
     * {@code com.cardemo.service.card.CardDetailService#getCardsByAccountId(String)}.
     *
     * <p>Consumers that need the non-paginated list of cards for an
     * account (using the CARDAIX alternate index) should inject the
     * {@code CardDetailService} bean directly rather than this update
     * service instance.</p>
     *
     * @param acctId ignored
     * @return never returns normally
     * @throws UnsupportedOperationException always
     */
    @Override
    public List<CardDto> getCardsByAccountId(String acctId) {
        throw new UnsupportedOperationException(
                "getCardsByAccountId is implemented by CardDetailService, not CardUpdateService");
    }

    /**
     * Not supported by {@code CardUpdateService}. Implemented by
     * {@code com.cardemo.service.card.CardListService#listCards(int, String, String)}.
     *
     * <p>Consumers that need the paginated card browse operation should
     * inject the {@code CardListService} bean directly (or the
     * {@link CardService} interface qualified to that bean) rather than
     * this update service instance.</p>
     *
     * @param page          ignored
     * @param acctIdFilter  ignored
     * @param cardNumFilter ignored
     * @return never returns normally
     * @throws UnsupportedOperationException always
     */
    @Override
    public Page<CardDto> listCards(int page, String acctIdFilter, String cardNumFilter) {
        throw new UnsupportedOperationException(
                "listCards is implemented by CardListService, not CardUpdateService");
    }

    /**
     * Not supported by {@code CardUpdateService}. Implemented by
     * {@code com.cardemo.service.card.CardListService#listCardsByAccount(String, int)}.
     *
     * <p>Consumers that need the account-scoped paginated card browse
     * operation should inject the {@code CardListService} bean directly
     * rather than this update service instance.</p>
     *
     * @param acctId ignored
     * @param page   ignored
     * @return never returns normally
     * @throws UnsupportedOperationException always
     */
    @Override
    public Page<CardDto> listCardsByAccount(String acctId, int page) {
        throw new UnsupportedOperationException(
                "listCardsByAccount is implemented by CardListService, not CardUpdateService");
    }
}
