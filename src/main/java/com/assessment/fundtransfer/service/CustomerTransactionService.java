package com.assessment.fundtransfer.service;

import com.assessment.fundtransfer.dto.request.CustomerTransactionRequest;
import com.assessment.fundtransfer.dto.response.BalanceResponse;
import com.assessment.fundtransfer.dto.response.CustomerTransactionResponse;
import com.assessment.fundtransfer.entity.CustomerTransactionDetail;
import com.assessment.fundtransfer.entity.TransactionType;
import com.assessment.fundtransfer.exception.AccountNotFoundException;
import com.assessment.fundtransfer.exception.DuplicateAccountException;
import com.assessment.fundtransfer.exception.DuplicateTransactionReferenceException;
import com.assessment.fundtransfer.exception.IdempotencyConflictException;
import com.assessment.fundtransfer.exception.InvalidRequestException;
import com.assessment.fundtransfer.exception.MissingIdempotencyKeyException;
import com.assessment.fundtransfer.repository.CustomerTransactionDetailRepository;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Service
public class CustomerTransactionService {

    private static final Logger log = LoggerFactory.getLogger(CustomerTransactionService.class);
    private static final Pattern ACCOUNT_NUMBER_PATTERN = Pattern.compile("^[A-Z0-9]{3,20}$");
    private static final Pattern REFERENCE_PATTERN = Pattern.compile("^[A-Z0-9_-]{3,64}$");
    private static final int MONEY_SCALE = 2;

    private final CustomerTransactionDetailRepository customerTransactionDetailRepository;
    private final TransactionOperations transactionOperations;

    @Autowired
    public CustomerTransactionService(
            CustomerTransactionDetailRepository customerTransactionDetailRepository,
            PlatformTransactionManager transactionManager
    ) {
        this(customerTransactionDetailRepository, new TransactionTemplate(transactionManager));
    }

    CustomerTransactionService(
            CustomerTransactionDetailRepository customerTransactionDetailRepository,
            TransactionOperations transactionOperations
    ) {
        this.customerTransactionDetailRepository = customerTransactionDetailRepository;
        this.transactionOperations = transactionOperations;
    }

    public IdempotentResult<CustomerTransactionResponse> saveCustomerTransactionDetails(
            CustomerTransactionRequest request,
            String idempotencyKey
    ) {
        String normalizedIdempotencyKey = normalizeIdempotencyKey(idempotencyKey);
        String customerId = normalizeRequired(request.customerId(), "Customer identifier is required");
        String customerName = normalizeRequired(request.customerName(), "Customer name is required");
        String accountNumber = normalizeAccountNumber(request.accountNumber());
        String transactionReference = normalizeReference(request.transactionReference(), "Transaction reference is required");
        BigDecimal transactionAmount = validateOpeningFundingAmount(request.transactionAmount());
        log.info("Customer transaction detail save requested for account {}", accountNumber);

        Optional<CustomerTransactionDetail> existingTransactionDetail =
                customerTransactionDetailRepository.findByIdempotencyKey(normalizedIdempotencyKey);
        if (existingTransactionDetail.isPresent()) {
            return buildIdempotentCustomerTransactionResult(
                    existingTransactionDetail.get(),
                    customerId,
                    customerName,
                    accountNumber,
                    transactionReference,
                    transactionAmount,
                    normalizedIdempotencyKey
            );
        }

        try {
            return Objects.requireNonNull(transactionOperations.execute(transactionStatus -> {
                if (customerTransactionDetailRepository.existsByAccountNumber(accountNumber)) {
                    log.warn("Customer transaction detail rejected because account {} already exists", accountNumber);
                    throw new DuplicateAccountException("Account number already exists");
                }
                if (customerTransactionDetailRepository.existsByTransactionReference(transactionReference)) {
                    log.warn(
                            "Customer transaction detail rejected because transactionReference={} already exists",
                            transactionReference
                    );
                    throw new DuplicateTransactionReferenceException("Transaction reference already exists");
                }

                CustomerTransactionDetail transactionDetail = new CustomerTransactionDetail();
                transactionDetail.setCustomerId(customerId);
                transactionDetail.setCustomerName(customerName);
                transactionDetail.setAccountNumber(accountNumber);
                transactionDetail.setTransactionReference(transactionReference);
                transactionDetail.setIdempotencyKey(normalizedIdempotencyKey);
                transactionDetail.setTransactionType(TransactionType.CREDIT);
                transactionDetail.setTransactionAmount(transactionAmount);
                transactionDetail.setCurrentBalance(transactionAmount);

                CustomerTransactionDetail savedTransactionDetail =
                        customerTransactionDetailRepository.saveAndFlush(transactionDetail);
                log.info("Customer transaction detail saved successfully for account {}", accountNumber);
                return new IdempotentResult<>(toResponse(savedTransactionDetail), false);
            }));
        } catch (DataIntegrityViolationException ex) {
            return recoverCustomerTransactionFromIdempotencyRace(
                    ex,
                    normalizedIdempotencyKey,
                    customerId,
                    customerName,
                    accountNumber,
                    transactionReference,
                    transactionAmount
            );
        }
    }

    @Transactional(readOnly = true)
    public BalanceResponse getBalance(String accountNumber) {
        String normalizedAccountNumber = normalizeAccountNumber(accountNumber);
        log.info("Balance lookup requested for account {}", normalizedAccountNumber);

        CustomerTransactionDetail transactionDetail = customerTransactionDetailRepository.findByAccountNumber(normalizedAccountNumber)
                .orElseThrow(() -> {
                    log.warn("Balance lookup failed because account {} was not found", normalizedAccountNumber);
                    return new AccountNotFoundException("Account not found");
                });
        log.info("Balance lookup succeeded for account {}", normalizedAccountNumber);
        return new BalanceResponse(
                transactionDetail.getCustomerId(),
                transactionDetail.getCustomerName(),
                transactionDetail.getAccountNumber(),
                transactionDetail.getCurrentBalance(),
                transactionDetail.getUpdatedAt()
        );
    }

    public String normalizeAccountNumber(String accountNumber) {
        String normalized = accountNumber == null ? null : accountNumber.trim().toUpperCase();
        if (normalized == null || normalized.isBlank() || !ACCOUNT_NUMBER_PATTERN.matcher(normalized).matches()) {
            throw new InvalidRequestException("Account number must be 3-20 alphanumeric characters");
        }
        return normalized;
    }

    public String normalizeReference(String reference, String missingMessage) {
        String normalized = reference == null ? null : reference.trim().toUpperCase();
        if (normalized == null || normalized.isBlank()) {
            throw new InvalidRequestException(missingMessage);
        }
        if (!REFERENCE_PATTERN.matcher(normalized).matches()) {
            throw new InvalidRequestException(
                    missingMessage.startsWith("Transaction")
                            ? "Transaction reference must be 3-64 characters using letters, numbers, hyphens, or underscores"
                            : "Transfer reference must be 3-64 characters using letters, numbers, hyphens, or underscores"
            );
        }
        return normalized;
    }

    public String normalizeRequired(String value, String message) {
        String normalized = value == null ? null : value.trim();
        if (normalized == null || normalized.isBlank()) {
            throw new InvalidRequestException(message);
        }
        return normalized;
    }

    public String normalizeIdempotencyKey(String idempotencyKey) {
        String normalized = idempotencyKey == null ? null : idempotencyKey.trim();
        if (normalized == null || normalized.isBlank()) {
            throw new MissingIdempotencyKeyException("Idempotency-Key header is required");
        }
        return normalized;
    }

    public BigDecimal validateOpeningFundingAmount(BigDecimal amount) {
        if (amount == null) {
            throw new InvalidRequestException("Transaction amount is required");
        }
        if (amount.compareTo(BigDecimal.ZERO) < 0) {
            throw new InvalidRequestException("Transaction amount cannot be negative");
        }
        if (amount.scale() > MONEY_SCALE) {
            throw new InvalidRequestException("Transaction amount must have at most 2 decimal places");
        }
        return amount;
    }

    public boolean isIdempotencyKeyConstraintViolation(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String normalizedMessage = message.toLowerCase();
                if (normalizedMessage.contains("idempotency")
                        && (normalizedMessage.contains("unique")
                        || normalizedMessage.contains("duplicate")
                        || normalizedMessage.contains("constraint"))) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private IdempotentResult<CustomerTransactionResponse> recoverCustomerTransactionFromIdempotencyRace(
            DataIntegrityViolationException ex,
            String normalizedIdempotencyKey,
            String customerId,
            String customerName,
            String accountNumber,
            String transactionReference,
            BigDecimal transactionAmount
    ) {
        if (!isIdempotencyKeyConstraintViolation(ex)) {
            throw ex;
        }

        CustomerTransactionDetail storedTransactionDetail =
                customerTransactionDetailRepository.findByIdempotencyKey(normalizedIdempotencyKey)
                        .orElseThrow(() -> ex);
        return buildIdempotentCustomerTransactionResult(
                storedTransactionDetail,
                customerId,
                customerName,
                accountNumber,
                transactionReference,
                transactionAmount,
                normalizedIdempotencyKey
        );
    }

    private IdempotentResult<CustomerTransactionResponse> buildIdempotentCustomerTransactionResult(
            CustomerTransactionDetail storedTransactionDetail,
            String customerId,
            String customerName,
            String accountNumber,
            String transactionReference,
            BigDecimal transactionAmount,
            String normalizedIdempotencyKey
    ) {
        boolean sameRequest = Objects.equals(storedTransactionDetail.getCustomerId(), customerId)
                && Objects.equals(storedTransactionDetail.getCustomerName(), customerName)
                && Objects.equals(storedTransactionDetail.getAccountNumber(), accountNumber)
                && Objects.equals(storedTransactionDetail.getTransactionReference(), transactionReference)
                && storedTransactionDetail.getTransactionAmount().compareTo(transactionAmount) == 0;
        if (!sameRequest) {
            log.warn(
                    "Customer transaction idempotency conflict for transactionReference={} and idempotencyKey={}",
                    transactionReference,
                    normalizedIdempotencyKey
            );
            throw new IdempotencyConflictException(
                    "Idempotency key was already used for a different customer transaction request"
            );
        }
        log.info(
                "Returning stored customer transaction detail replay for transactionReference={} and idempotencyKey={}",
                storedTransactionDetail.getTransactionReference(),
                normalizedIdempotencyKey
        );
        return new IdempotentResult<>(toResponse(storedTransactionDetail), true);
    }

    private CustomerTransactionResponse toResponse(CustomerTransactionDetail transactionDetail) {
        return new CustomerTransactionResponse(
                transactionDetail.getId(),
                transactionDetail.getCustomerId(),
                transactionDetail.getCustomerName(),
                transactionDetail.getAccountNumber(),
                transactionDetail.getTransactionReference(),
                transactionDetail.getTransactionAmount(),
                transactionDetail.getCurrentBalance(),
                transactionDetail.getCreatedAt()
        );
    }
}
