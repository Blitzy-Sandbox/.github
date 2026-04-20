package com.cardemo.service.account;

import com.cardemo.domain.validation.AccountValidator;
import com.cardemo.exception.ConcurrentModificationException;
import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.AccountDto;
import com.cardemo.model.entity.Account;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.model.entity.Customer;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CardCrossReferenceRepository;
import com.cardemo.repository.CustomerRepository;
import com.cardemo.service.interfaces.AccountService;
import com.cardemo.service.shared.DateValidationService;
import com.cardemo.service.shared.ValidationLookupService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Account Update Service — migrated from COBOL program COACTUPC.cbl (4,236 lines).
 *
 * <p>Performs dual-dataset atomic updates (Account + Customer) within a single
 * {@code @Transactional} boundary, with optimistic concurrency control via JPA
 * {@code @Version}, extensive field-level validation, and SYNCPOINT ROLLBACK semantics.</p>
 *
 * <h3>COBOL Paragraph Mapping:</h3>
 * <ul>
 *   <li>9000-READ-ACCT chain → {@link #getAccount(String)}</li>
 *   <li>PROCESS-UPDATE-ACCT → {@link #updateAccount(String, AccountDto)}</li>
 *   <li>1200-EDIT-MAP-INPUTS → {@link com.cardemo.domain.validation.AccountValidator#validateUpdateFields(AccountDto)}</li>
 *   <li>9600-WRITE-PROCESSING → save section in updateAccount</li>
 *   <li>9700-CHECK-CHANGE-IN-REC → JPA {@code @Version} optimistic locking</li>
 *   <li>SYNCPOINT ROLLBACK → {@code @Transactional(rollbackFor = Exception.class)}</li>
 * </ul>
 *
 * @see Account
 * @see Customer
 * @see AccountDto
 */
@Service
public class AccountUpdateService implements AccountService {

    private static final Logger logger = LoggerFactory.getLogger(AccountUpdateService.class);

    private final AccountRepository accountRepository;
    private final CustomerRepository customerRepository;
    private final CardCrossReferenceRepository cardCrossReferenceRepository;
    private final DateValidationService dateValidationService;
    private final ValidationLookupService validationLookupService;
    private final AccountValidator accountValidator;

    /**
     * Constructor injection of all 6 dependencies.
     *
     * @param accountRepository           JPA repository for Account entity (ACCTDAT VSAM)
     * @param customerRepository          JPA repository for Customer entity (CUSTDAT VSAM)
     * @param cardCrossReferenceRepository JPA repository for cross-reference (CXACAIX alternate index)
     * @param dateValidationService       Shared date validation (replaces CSUTLDTC.cbl + CEEDAYS)
     * @param validationLookupService     Shared lookup service (replaces CSLKPCDY.cpy tables)
     * @param accountValidator            Domain validator extracted from former private validation methods (AAP Section 0.5.1)
     */
    public AccountUpdateService(AccountRepository accountRepository,
                                CustomerRepository customerRepository,
                                CardCrossReferenceRepository cardCrossReferenceRepository,
                                DateValidationService dateValidationService,
                                ValidationLookupService validationLookupService,
                                AccountValidator accountValidator) {
        this.accountRepository = accountRepository;
        this.customerRepository = customerRepository;
        this.cardCrossReferenceRepository = cardCrossReferenceRepository;
        this.dateValidationService = dateValidationService;
        this.validationLookupService = validationLookupService;
        this.accountValidator = accountValidator;
    }

    // =========================================================================
    // Public Methods
    // =========================================================================

    /**
     * Fetches account data for the update form.
     * Maps COBOL COACTUPC.cbl paragraphs 9000-READ-ACCT → 9100-GETACCT-REQUEST →
     * 9200-GETCARDXREF-REQUEST (CXACAIX) → 9400-GETCUSTDATA.
     *
     * <p>Performs a 3-step read chain: cross-reference lookup → account fetch → customer fetch.</p>
     *
     * @param acctId the 11-digit account identifier
     * @return populated AccountDto combining account and customer data
     * @throws RecordNotFoundException if account, cross-reference, or customer not found
     * @throws ValidationException     if account ID is invalid
     */
    @Override
    public AccountDto getAccount(String acctId) {
        logger.info("Fetching account for update: acctId='{}'", acctId);

        accountValidator.validateAccountId(acctId);

        // Step 1: Cross-reference lookup to resolve customer ID (← CXACAIX alternate index path)
        List<CardCrossReference> xrefs = cardCrossReferenceRepository.findByXrefAcctId(acctId);
        if (xrefs.isEmpty()) {
            logger.warn("No cross-reference found for account: {}", acctId);
            throw new RecordNotFoundException("CardCrossReference", acctId);
        }
        CardCrossReference xref = xrefs.getFirst();
        String custId = xref.getXrefCustId();
        logger.debug("Cross-reference resolved: acctId='{}' → custId='{}'", acctId, custId);

        // Step 2: Fetch account record (← EXEC CICS READ FILE(ACCTFILENAME))
        Account account = accountRepository.findById(acctId)
                .orElseThrow(() -> {
                    logger.error("Account not found: {}", acctId);
                    return new RecordNotFoundException("Account", acctId);
                });

        // Step 3: Fetch customer record (← EXEC CICS READ FILE(CUSTFILENAME))
        Customer customer = customerRepository.findById(custId)
                .orElseThrow(() -> {
                    logger.error("Customer not found: {}", custId);
                    return new RecordNotFoundException("Customer", custId);
                });

        logger.info("Account fetched successfully for update: acctId='{}', version={}", acctId, account.getVersion());
        return assembleAccountDto(account, customer);
    }

    /**
     * Performs the full account update with atomic dual-dataset persistence.
     * Maps COBOL COACTUPC.cbl PROCESS-UPDATE-ACCT → 9600-WRITE-PROCESSING.
     *
     * <p>The {@code @Transactional(rollbackFor = Exception.class)} annotation maps the COBOL
     * EXEC CICS SYNCPOINT ROLLBACK semantics — if the customer REWRITE fails after the
     * account REWRITE succeeds, the entire transaction is rolled back.</p>
     *
     * <p>Optimistic concurrency control is enforced via JPA {@code @Version} on the
     * Account entity, mapping COACTUPC 9700-CHECK-CHANGE-IN-REC (DATA-WAS-CHANGED-BEFORE-UPDATE).</p>
     *
     * @param acctId      the 11-digit account identifier
     * @param updatedData the DTO containing updated field values
     * @return AccountDto with the persisted updated values
     * @throws ValidationException               if field validation fails (25+ field cascade)
     * @throws RecordNotFoundException           if account, cross-reference, or customer not found
     * @throws ConcurrentModificationException   if optimistic lock conflict detected
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public AccountDto updateAccount(String acctId, AccountDto updatedData) {
        logger.info("Starting account update: acctId='{}'", acctId);

        // Step 1: Validate ALL input fields (← 1200-EDIT-MAP-INPUTS)
        // Aggregates all errors before throwing — matches COBOL aggregate validation pattern
        accountValidator.validateUpdateFields(updatedData);

        // Step 2: Fetch current records (← 9600-WRITE-PROCESSING READ UPDATE)
        Account account = accountRepository.findById(acctId)
                .orElseThrow(() -> {
                    logger.error("Account not found during update: {}", acctId);
                    return new RecordNotFoundException("Account", acctId);
                });

        List<CardCrossReference> xrefs = cardCrossReferenceRepository.findByXrefAcctId(acctId);
        if (xrefs.isEmpty()) {
            logger.error("Cross-reference not found for account during update: {}", acctId);
            throw new RecordNotFoundException("CardCrossReference", acctId);
        }
        String custId = xrefs.getFirst().getXrefCustId();

        Customer customer = customerRepository.findById(custId)
                .orElseThrow(() -> {
                    logger.error("Customer not found during update: {}", custId);
                    return new RecordNotFoundException("Customer", custId);
                });

        logger.debug("Current account version before update: {}", account.getVersion());

        // Step 2.5: Optimistic concurrency control — compare client-supplied version with DB version
        // Maps COACTUPC 9700-CHECK-CHANGE-IN-REC (DATA-WAS-CHANGED-BEFORE-UPDATE).
        // If the client-supplied version does not match the current DB version, the record
        // was modified by another transaction between the client's GET and this PUT.
        if (updatedData.getVersion() != null
                && !updatedData.getVersion().equals(account.getVersion())) {
            logger.error("Optimistic lock conflict: client version={}, DB version={} for account {}",
                    updatedData.getVersion(), account.getVersion(), acctId);
            throw new ConcurrentModificationException(
                    "Account " + acctId + " was modified by another transaction. "
                            + "Expected version " + updatedData.getVersion()
                            + " but found version " + account.getVersion()
                            + ". Please refresh and retry.");
        }

        // Step 3: Apply updates to Account entity (← prepare ACCT-UPDATE-RECORD)
        applyAccountUpdates(account, updatedData);

        // Step 4: Apply updates to Customer entity (← prepare CUST-UPDATE-RECORD)
        applyCustomerUpdates(customer, updatedData);

        // Step 5: Persist both records atomically (← REWRITE ACCTDAT + REWRITE CUSTDAT)
        // If ObjectOptimisticLockingFailureException occurs → maps 9700-CHECK-CHANGE-IN-REC
        // @Transactional ensures SYNCPOINT ROLLBACK on any exception
        // saveAndFlush() ensures JPA @Version is incremented immediately so the
        // returned entity reflects the post-save version number (fixes stale version
        // in PUT response — the DTO must carry the updated version for optimistic locking
        // by subsequent client requests).
        Account savedAccount;
        Customer savedCustomer;
        try {
            savedAccount = accountRepository.saveAndFlush(account);
            savedCustomer = customerRepository.saveAndFlush(customer);
        } catch (ObjectOptimisticLockingFailureException ex) {
            logger.error("Concurrent modification detected for account: {}", acctId, ex);
            throw new ConcurrentModificationException(
                    "Account " + acctId + " was modified by another transaction. "
                            + "Please refresh and retry.", ex);
        }

        logger.info("Account update completed successfully: acctId='{}'", acctId);

        // Step 6: Return updated AccountDto using the post-save entities (with incremented version)
        return assembleAccountDto(savedAccount, savedCustomer);
    }

    /**
     * Not applicable to the account update service. The read-only account view
     * (which joins account, card cross-reference, and customer tables to produce a
     * composite display DTO) is handled by
     * {@link com.cardemo.service.account.AccountViewService#getAccountView(String)}.
     *
     * <p>This method exists solely to satisfy the {@link AccountService} interface contract
     * per the aggregated-interface pattern documented in AAP Section 0.7.3. Calling it on
     * this service throws {@link UnsupportedOperationException}; consumers wishing to obtain
     * the CAVW-style read-only view must call {@code AccountViewService.getAccountView(acctId)}
     * directly or rely on the {@code @Primary AccountServiceImpl} aggregator which delegates
     * view operations to {@code AccountViewService}.</p>
     *
     * @param acctId the 11-digit account identifier (not used)
     * @return never returns
     * @throws UnsupportedOperationException always
     */
    @Override
    public AccountDto getAccountView(String acctId) {
        throw new UnsupportedOperationException(
                "getAccountView is handled by AccountViewService, not AccountUpdateService");
    }

    // =========================================================================
    // Entity Update Helpers
    // =========================================================================

    /**
     * Applies field updates from DTO to Account entity.
     * Maps COBOL COACTUPC prepare ACCT-UPDATE-RECORD section.
     * Only updates fields that are non-null in the DTO (preserves COBOL LOW-VALUES sentinel pattern).
     */
    private void applyAccountUpdates(Account account, AccountDto dto) {
        if (dto.getAcctActiveStatus() != null) {
            account.setAcctActiveStatus(dto.getAcctActiveStatus().toUpperCase());
        }
        if (dto.getAcctCurrBal() != null) {
            account.setAcctCurrBal(dto.getAcctCurrBal());
        }
        if (dto.getAcctCreditLimit() != null) {
            account.setAcctCreditLimit(dto.getAcctCreditLimit());
        }
        if (dto.getAcctCashCreditLimit() != null) {
            account.setAcctCashCreditLimit(dto.getAcctCashCreditLimit());
        }
        if (dto.getAcctOpenDate() != null) {
            account.setAcctOpenDate(dto.getAcctOpenDate());
        }
        if (dto.getAcctExpDate() != null) {
            account.setAcctExpDate(dto.getAcctExpDate());
        }
        if (dto.getAcctReissueDate() != null) {
            account.setAcctReissueDate(dto.getAcctReissueDate());
        }
        if (dto.getAcctCurrCycCredit() != null) {
            account.setAcctCurrCycCredit(dto.getAcctCurrCycCredit());
        }
        if (dto.getAcctCurrCycDebit() != null) {
            account.setAcctCurrCycDebit(dto.getAcctCurrCycDebit());
        }
        if (dto.getAcctGroupId() != null) {
            account.setAcctGroupId(dto.getAcctGroupId());
        }
    }

    /**
     * Applies field updates from DTO to Customer entity.
     * Maps COBOL COACTUPC prepare CUST-UPDATE-RECORD section.
     * Only updates fields that are non-null in the DTO (preserves COBOL LOW-VALUES sentinel pattern).
     *
     * <p>Note: Customer entity does NOT have {@code @Version} — only Account uses optimistic locking.</p>
     */
    private void applyCustomerUpdates(Customer customer, AccountDto dto) {
        if (dto.getCustFname() != null) {
            customer.setCustFirstName(dto.getCustFname());
        }
        if (dto.getCustMname() != null) {
            customer.setCustMiddleName(dto.getCustMname());
        }
        if (dto.getCustLname() != null) {
            customer.setCustLastName(dto.getCustLname());
        }
        if (dto.getCustAddr1() != null) {
            customer.setCustAddrLine1(dto.getCustAddr1());
        }
        if (dto.getCustAddr2() != null) {
            customer.setCustAddrLine2(dto.getCustAddr2());
        }
        // City is stored in address line 3 per COBOL CUST-ADDR-LINE-3 mapping
        if (dto.getCustCity() != null) {
            customer.setCustAddrLine3(dto.getCustCity());
        }
        if (dto.getCustState() != null) {
            customer.setCustAddrStateCd(dto.getCustState().toUpperCase());
        }
        if (dto.getCustZip() != null) {
            customer.setCustAddrZip(dto.getCustZip());
        }
        if (dto.getCustCountry() != null) {
            customer.setCustAddrCountryCd(dto.getCustCountry().toUpperCase());
        }
        if (dto.getCustPhone1() != null) {
            customer.setCustPhoneNum1(dto.getCustPhone1());
        }
        if (dto.getCustPhone2() != null) {
            customer.setCustPhoneNum2(dto.getCustPhone2());
        }
        // SSN: strip formatting dashes for entity storage (entity field is 9 raw digits)
        if (dto.getCustSsn() != null) {
            customer.setCustSsn(stripNonDigits(dto.getCustSsn()));
        }
        if (dto.getCustDob() != null) {
            customer.setCustDob(dto.getCustDob());
        }
        // FICO: DTO stores as String, entity stores as Short
        if (!isBlankOrNull(dto.getCustFicoScore())) {
            customer.setCustFicoCreditScore(Short.parseShort(dto.getCustFicoScore().trim()));
        }
        if (dto.getCustGovtId() != null) {
            customer.setCustGovtIssuedId(dto.getCustGovtId());
        }
        if (dto.getCustEftAcct() != null) {
            customer.setCustEftAccountId(dto.getCustEftAcct());
        }
        // Profile flag maps to Primary Card Holder Indicator
        if (dto.getCustProfileFlag() != null) {
            customer.setCustPriCardHolderInd(dto.getCustProfileFlag().toUpperCase());
        }
    }

    /**
     * Assembles an AccountDto from Account and Customer entities.
     * Combines data from both datasets into a single DTO for API response,
     * mirroring the COBOL BMS map send operation that displays data from
     * both ACCTDAT and CUSTDAT simultaneously.
     */
    private AccountDto assembleAccountDto(Account account, Customer customer) {
        AccountDto dto = new AccountDto();

        // Account fields
        dto.setAcctId(account.getAcctId());
        dto.setAcctActiveStatus(account.getAcctActiveStatus());
        dto.setAcctCurrBal(account.getAcctCurrBal());
        dto.setAcctCreditLimit(account.getAcctCreditLimit());
        dto.setAcctCashCreditLimit(account.getAcctCashCreditLimit());
        dto.setAcctOpenDate(account.getAcctOpenDate());
        dto.setAcctExpDate(account.getAcctExpDate());
        dto.setAcctReissueDate(account.getAcctReissueDate());
        dto.setAcctCurrCycCredit(account.getAcctCurrCycCredit());
        dto.setAcctCurrCycDebit(account.getAcctCurrCycDebit());
        dto.setAcctGroupId(account.getAcctGroupId());

        // Customer fields
        dto.setCustId(customer.getCustId());
        dto.setCustFname(customer.getCustFirstName());
        dto.setCustMname(customer.getCustMiddleName());
        dto.setCustLname(customer.getCustLastName());
        dto.setCustAddr1(customer.getCustAddrLine1());
        dto.setCustAddr2(customer.getCustAddrLine2());
        dto.setCustCity(customer.getCustAddrLine3()); // City stored in addr-line-3
        dto.setCustState(customer.getCustAddrStateCd());
        dto.setCustZip(customer.getCustAddrZip());
        dto.setCustCountry(customer.getCustAddrCountryCd());
        dto.setCustPhone1(customer.getCustPhoneNum1());
        dto.setCustPhone2(customer.getCustPhoneNum2());
        dto.setCustSsn(customer.getCustSsn());
        dto.setCustDob(customer.getCustDob());
        dto.setCustFicoScore(customer.getCustFicoCreditScore() != null
                ? String.valueOf(customer.getCustFicoCreditScore()) : null);
        dto.setCustGovtId(customer.getCustGovtIssuedId());
        dto.setCustEftAcct(customer.getCustEftAccountId());
        dto.setCustProfileFlag(customer.getCustPriCardHolderInd());

        // Optimistic locking version — exposes JPA @Version for concurrent modification
        // detection via API. Clients must include this in PUT requests; a mismatch
        // triggers HTTP 409 Conflict per AAP §0.8.4.
        dto.setVersion(account.getVersion());

        return dto;
    }

    // =========================================================================
    // Utility Methods
    // =========================================================================

    /**
     * Strips all non-digit characters from a string.
     * Used to normalize phone numbers and SSNs that may contain
     * formatting characters (dashes, parentheses, spaces).
     *
     * @param value the input string
     * @return string containing only digit characters
     */
    private String stripNonDigits(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("[^0-9]", "");
    }

    /**
     * Checks if a string is null, empty, or contains only whitespace.
     * Maps COBOL SPACES / LOW-VALUES sentinel check.
     *
     * @param value the string to check
     * @return true if the value is effectively blank
     */
    private boolean isBlankOrNull(String value) {
        return value == null || value.trim().isEmpty();
    }
}
