package com.assessment.fundtransfer.service;

import com.assessment.fundtransfer.dto.request.TransferRequest;
import com.assessment.fundtransfer.dto.response.TransferResponse;
import com.assessment.fundtransfer.entity.CustomerTransactionDetail;
import com.assessment.fundtransfer.entity.TransactionStatus;
import com.assessment.fundtransfer.entity.TransferTransaction;
import com.assessment.fundtransfer.exception.AccountNotFoundException;
import com.assessment.fundtransfer.exception.DuplicateTransferReferenceException;
import com.assessment.fundtransfer.exception.IdempotencyConflictException;
import com.assessment.fundtransfer.exception.InvalidRequestException;
import com.assessment.fundtransfer.exception.InsufficientFundsException;
import com.assessment.fundtransfer.repository.CustomerTransactionDetailRepository;
import com.assessment.fundtransfer.repository.TransferTransactionRepository;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.PessimisticLockException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);
    private static final int MONEY_SCALE = 2;
    private static final int IDEMPOTENCY_RECOVERY_ATTEMPTS = 5;
    private static final long IDEMPOTENCY_RECOVERY_DELAY_MILLIS = 25L;

    private final CustomerTransactionDetailRepository customerTransactionDetailRepository;
    private final TransferTransactionRepository transactionRepository;
    private final CustomerTransactionService customerTransactionService;
    private final TransactionOperations transactionOperations;

    @Autowired
    public TransferService(
            CustomerTransactionDetailRepository customerTransactionDetailRepository,
            TransferTransactionRepository transactionRepository,
            CustomerTransactionService customerTransactionService,
            PlatformTransactionManager transactionManager
    ) {
        this(
                customerTransactionDetailRepository,
                transactionRepository,
                customerTransactionService,
                new TransactionTemplate(transactionManager)
        );
    }

    TransferService(
            CustomerTransactionDetailRepository customerTransactionDetailRepository,
            TransferTransactionRepository transactionRepository,
            CustomerTransactionService customerTransactionService,
            TransactionOperations transactionOperations
    ) {
        this.customerTransactionDetailRepository = customerTransactionDetailRepository;
        this.transactionRepository = transactionRepository;
        this.customerTransactionService = customerTransactionService;
        this.transactionOperations = transactionOperations;
    }

    public IdempotentResult<TransferResponse> transfer(TransferRequest request, String idempotencyKey) {
        String normalizedIdempotencyKey = customerTransactionService.normalizeIdempotencyKey(idempotencyKey);
        String sourceAccountNumber = customerTransactionService.normalizeAccountNumber(request.sourceAccountNumber());
        String destinationAccountNumber = customerTransactionService.normalizeAccountNumber(request.destinationAccountNumber());
        BigDecimal amount = validateTransferAmount(request.amount());
        String transferReference = customerTransactionService.normalizeReference(
                request.transferReference(),
                "Transfer reference is required"
        );

        TransferTransaction existingTransfer = transactionRepository.findByIdempotencyKey(normalizedIdempotencyKey).orElse(null);
        if (existingTransfer != null) {
            return buildIdempotentTransferResult(
                    existingTransfer,
                    sourceAccountNumber,
                    destinationAccountNumber,
                    transferReference,
                    amount,
                    normalizedIdempotencyKey
            );
        }

        log.info(
                "Transfer requested from account {} to account {} for amount {} with transferReference={} and idempotencyKey={}",
                sourceAccountNumber,
                destinationAccountNumber,
                amount,
                transferReference,
                normalizedIdempotencyKey
        );

        if (sourceAccountNumber.equals(destinationAccountNumber)) {
            log.warn("Transfer rejected because source and destination account {} are identical", sourceAccountNumber);
            throw new InvalidRequestException("Source and destination accounts must be different");
        }

        try {
            return Objects.requireNonNull(transactionOperations.execute(transactionStatus -> {
                if (transactionRepository.existsByTransferReference(transferReference)) {
                    log.warn("Transfer rejected because transferReference={} already exists", transferReference);
                    throw new DuplicateTransferReferenceException("Transfer reference already exists");
                }

                Map<String, CustomerTransactionDetail> lockedTransactionDetails =
                        lockCustomersInStableOrder(sourceAccountNumber, destinationAccountNumber);
                CustomerTransactionDetail source = lockedTransactionDetails.get(sourceAccountNumber);
                CustomerTransactionDetail destination = lockedTransactionDetails.get(destinationAccountNumber);

                BigDecimal sourceBalanceBefore = source.getCurrentBalance();
                BigDecimal destinationBalanceBefore = destination.getCurrentBalance();

                if (sourceBalanceBefore.compareTo(amount) < 0) {
                    log.warn("Transfer rejected due to insufficient funds for account {}", sourceAccountNumber);
                    throw new InsufficientFundsException("Insufficient funds");
                }

                BigDecimal sourceBalanceAfter = sourceBalanceBefore.subtract(amount);
                BigDecimal destinationBalanceAfter = destinationBalanceBefore.add(amount);

                source.setCurrentBalance(sourceBalanceAfter);
                destination.setCurrentBalance(destinationBalanceAfter);

                customerTransactionDetailRepository.save(source);
                customerTransactionDetailRepository.save(destination);

                TransferTransaction transaction = new TransferTransaction();
                transaction.setTransferReference(transferReference);
                transaction.setIdempotencyKey(normalizedIdempotencyKey);
                transaction.setSourceAccountNumber(sourceAccountNumber);
                transaction.setDestinationAccountNumber(destinationAccountNumber);
                transaction.setAmount(amount);
                transaction.setSourceBalanceAfterTransfer(sourceBalanceAfter);
                transaction.setDestinationBalanceAfterTransfer(destinationBalanceAfter);
                transaction.setStatus(TransactionStatus.SUCCESS);

                TransferTransaction savedTransaction = transactionRepository.saveAndFlush(transaction);
                log.info(
                        "Transfer completed successfully. transferReference={}, idempotencyKey={}, sourceAccount={}, destinationAccount={}",
                        savedTransaction.getTransferReference(),
                        savedTransaction.getIdempotencyKey(),
                        sourceAccountNumber,
                        destinationAccountNumber
                );

                return new IdempotentResult<>(toResponse(savedTransaction), false);
            }));
        } catch (DataIntegrityViolationException ex) {
            return recoverTransferFromIdempotencyRace(
                    ex,
                    normalizedIdempotencyKey,
                    sourceAccountNumber,
                    destinationAccountNumber,
                    transferReference,
                    amount
            );
        } catch (RuntimeException ex) {
            return recoverTransferAfterLockConflict(
                    ex,
                    normalizedIdempotencyKey,
                    sourceAccountNumber,
                    destinationAccountNumber,
                    transferReference,
                    amount
            );
        }
    }

    private TransferResponse toResponse(TransferTransaction transaction) {
        return new TransferResponse(
                transaction.getTransferReference(),
                transaction.getSourceAccountNumber(),
                transaction.getDestinationAccountNumber(),
                transaction.getAmount(),
                transaction.getSourceBalanceAfterTransfer(),
                transaction.getDestinationBalanceAfterTransfer(),
                transaction.getStatus(),
                transaction.getCreatedAt()
        );
    }

    private Map<String, CustomerTransactionDetail> lockCustomersInStableOrder(
            String sourceAccountNumber,
            String destinationAccountNumber
    ) {
        List<String> orderedAccountNumbers = List.of(sourceAccountNumber, destinationAccountNumber).stream()
                .sorted(Comparator.naturalOrder())
                .toList();
        Map<String, CustomerTransactionDetail> lockedCustomers = new HashMap<>();

        for (String accountNumber : orderedAccountNumbers) {
            CustomerTransactionDetail customerTransactionDetail =
                    customerTransactionDetailRepository.findByAccountNumberForUpdate(accountNumber)
                    .orElseThrow(() -> {
                        String message = accountNumber.equals(sourceAccountNumber)
                                ? "Source account not found"
                                : "Destination account not found";
                        log.warn("Transfer rejected because {} ({}) was not found", message, accountNumber);
                        return new AccountNotFoundException(message);
                    });
            lockedCustomers.put(accountNumber, customerTransactionDetail);
        }

        return lockedCustomers;
    }

    private BigDecimal validateTransferAmount(BigDecimal amount) {
        if (amount == null) {
            throw new InvalidRequestException("Amount is required");
        }
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("Transfer rejected because amount {} is not positive", amount);
            throw new InvalidRequestException("Amount must be greater than zero");
        }
        if (amount.scale() > MONEY_SCALE) {
            throw new InvalidRequestException("Amount must have at most 2 decimal places");
        }
        return amount;
    }

    private IdempotentResult<TransferResponse> recoverTransferFromIdempotencyRace(
            DataIntegrityViolationException ex,
            String normalizedIdempotencyKey,
            String sourceAccountNumber,
            String destinationAccountNumber,
            String transferReference,
            BigDecimal amount
    ) {
        if (!customerTransactionService.isIdempotencyKeyConstraintViolation(ex)) {
            throw ex;
        }

        TransferTransaction storedTransfer = transactionRepository.findByIdempotencyKey(normalizedIdempotencyKey)
                .orElseThrow(() -> ex);
        return buildIdempotentTransferResult(
                storedTransfer,
                sourceAccountNumber,
                destinationAccountNumber,
                transferReference,
                amount,
                normalizedIdempotencyKey
        );
    }

    private IdempotentResult<TransferResponse> recoverTransferAfterLockConflict(
            RuntimeException ex,
            String normalizedIdempotencyKey,
            String sourceAccountNumber,
            String destinationAccountNumber,
            String transferReference,
            BigDecimal amount
    ) {
        if (!isLockConflict(ex)) {
            throw ex;
        }

        for (int attempt = 0; attempt < IDEMPOTENCY_RECOVERY_ATTEMPTS; attempt++) {
            TransferTransaction storedTransfer = transactionRepository.findByIdempotencyKey(normalizedIdempotencyKey)
                    .orElse(null);
            if (storedTransfer != null) {
                return buildIdempotentTransferResult(
                        storedTransfer,
                        sourceAccountNumber,
                        destinationAccountNumber,
                        transferReference,
                        amount,
                        normalizedIdempotencyKey
                );
            }
            sleepBeforeIdempotencyRecovery();
        }

        throw ex;
    }

    private boolean isLockConflict(RuntimeException ex) {
        return ex instanceof PessimisticLockingFailureException
                || ex instanceof CannotAcquireLockException
                || ex instanceof LockTimeoutException
                || ex instanceof PessimisticLockException;
    }

    private void sleepBeforeIdempotencyRecovery() {
        try {
            Thread.sleep(IDEMPOTENCY_RECOVERY_DELAY_MILLIS);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while recovering idempotent transfer", interruptedException);
        }
    }

    private IdempotentResult<TransferResponse> buildIdempotentTransferResult(
            TransferTransaction storedTransfer,
            String sourceAccountNumber,
            String destinationAccountNumber,
            String transferReference,
            BigDecimal amount,
            String normalizedIdempotencyKey
    ) {
        boolean sameRequest = Objects.equals(storedTransfer.getSourceAccountNumber(), sourceAccountNumber)
                && Objects.equals(storedTransfer.getDestinationAccountNumber(), destinationAccountNumber)
                && Objects.equals(storedTransfer.getTransferReference(), transferReference)
                && storedTransfer.getAmount().compareTo(amount) == 0;
        if (!sameRequest) {
            log.warn(
                    "Transfer idempotency conflict for transferReference={} and idempotencyKey={}",
                    transferReference,
                    normalizedIdempotencyKey
            );
            throw new IdempotencyConflictException(
                    "Idempotency key was already used for a different transfer request"
            );
        }
        log.info(
                "Returning stored transfer replay for transferReference={} and idempotencyKey={}",
                storedTransfer.getTransferReference(),
                normalizedIdempotencyKey
        );
        return new IdempotentResult<>(toResponse(storedTransfer), true);
    }
}
