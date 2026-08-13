package com.assessment.fundtransfer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.assessment.fundtransfer.dto.request.TransferRequest;
import com.assessment.fundtransfer.dto.response.TransferResponse;
import com.assessment.fundtransfer.entity.CustomerTransactionDetail;
import com.assessment.fundtransfer.entity.TransactionStatus;
import com.assessment.fundtransfer.entity.TransactionType;
import com.assessment.fundtransfer.entity.TransferTransaction;
import com.assessment.fundtransfer.exception.AccountNotFoundException;
import com.assessment.fundtransfer.exception.DuplicateTransferReferenceException;
import com.assessment.fundtransfer.exception.IdempotencyConflictException;
import com.assessment.fundtransfer.exception.InvalidRequestException;
import com.assessment.fundtransfer.exception.InsufficientFundsException;
import com.assessment.fundtransfer.exception.MissingIdempotencyKeyException;
import com.assessment.fundtransfer.repository.CustomerTransactionDetailRepository;
import com.assessment.fundtransfer.repository.TransferTransactionRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

@ExtendWith(MockitoExtension.class)
class TransferServiceTest {

    @Mock
    private CustomerTransactionDetailRepository customerTransactionDetailRepository;

    @Mock
    private TransferTransactionRepository transactionRepository;

    @Mock
    private CustomerTransactionService customerTransactionService;

    private TransferService transferService;

    private CustomerTransactionDetail source;
    private CustomerTransactionDetail destination;

    @BeforeEach
    void setUp() {
        TransactionOperations transactionOperations = new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(null);
            }
        };
        transferService = new TransferService(
                customerTransactionDetailRepository,
                transactionRepository,
                customerTransactionService,
                transactionOperations
        );

        source = new CustomerTransactionDetail();
        source.setAccountNumber("ACC001");
        source.setTransactionType(TransactionType.CREDIT);
        source.setCurrentBalance(new BigDecimal("5000.00"));

        destination = new CustomerTransactionDetail();
        destination.setAccountNumber("ACC002");
        destination.setTransactionType(TransactionType.CREDIT);
        destination.setCurrentBalance(new BigDecimal("2000.00"));
    }

    @Test
    void shouldTransferFundsSuccessfully() {
        TransferRequest request = new TransferRequest("acc001", "acc002", new BigDecimal("1000.00"), " inv-001 ");
        TransferTransaction transaction = new TransferTransaction();
        transaction.setTransferReference("INV-001");
        transaction.setIdempotencyKey("idem-1");
        transaction.setSourceAccountNumber("ACC001");
        transaction.setDestinationAccountNumber("ACC002");
        transaction.setAmount(new BigDecimal("1000.00"));
        transaction.setSourceBalanceAfterTransfer(new BigDecimal("4000.00"));
        transaction.setDestinationBalanceAfterTransfer(new BigDecimal("3000.00"));
        transaction.setStatus(TransactionStatus.SUCCESS);
        transaction.setCreatedAt(LocalDateTime.now());

        when(customerTransactionService.normalizeIdempotencyKey("idem-1")).thenReturn("idem-1");
        when(customerTransactionService.normalizeAccountNumber("acc001")).thenReturn("ACC001");
        when(customerTransactionService.normalizeAccountNumber("acc002")).thenReturn("ACC002");
        when(customerTransactionService.normalizeReference(" inv-001 ", "Transfer reference is required"))
                .thenReturn("INV-001");
        when(transactionRepository.findByIdempotencyKey("idem-1")).thenReturn(Optional.empty());
        when(transactionRepository.existsByTransferReference("INV-001")).thenReturn(false);
        when(customerTransactionDetailRepository.findByAccountNumberForUpdate("ACC001")).thenReturn(Optional.of(source));
        when(customerTransactionDetailRepository.findByAccountNumberForUpdate("ACC002")).thenReturn(Optional.of(destination));
        when(transactionRepository.saveAndFlush(any(TransferTransaction.class))).thenReturn(transaction);

        IdempotentResult<TransferResponse> result = transferService.transfer(request, "idem-1");

        assertThat(result.replayed()).isFalse();
        assertThat(source.getCurrentBalance()).isEqualByComparingTo("4000.00");
        assertThat(destination.getCurrentBalance()).isEqualByComparingTo("3000.00");
        assertThat(result.payload().transferReference()).isEqualTo("INV-001");
        verify(transactionRepository).saveAndFlush(any(TransferTransaction.class));
    }

    @Test
    void shouldReplayTransferWhenConcurrentIdenticalInsertClaimsIdempotencyKey() {
        TransferRequest request = new TransferRequest("acc001", "acc002", new BigDecimal("1000.00"), " inv-001 ");
        TransferTransaction stored = new TransferTransaction();
        stored.setTransferReference("INV-001");
        stored.setIdempotencyKey("idem-race");
        stored.setSourceAccountNumber("ACC001");
        stored.setDestinationAccountNumber("ACC002");
        stored.setAmount(new BigDecimal("1000.00"));
        stored.setSourceBalanceAfterTransfer(new BigDecimal("4000.00"));
        stored.setDestinationBalanceAfterTransfer(new BigDecimal("3000.00"));
        stored.setStatus(TransactionStatus.SUCCESS);
        stored.setCreatedAt(LocalDateTime.now());

        when(customerTransactionService.normalizeIdempotencyKey("idem-race")).thenReturn("idem-race");
        when(customerTransactionService.normalizeAccountNumber("acc001")).thenReturn("ACC001");
        when(customerTransactionService.normalizeAccountNumber("acc002")).thenReturn("ACC002");
        when(customerTransactionService.normalizeReference(" inv-001 ", "Transfer reference is required"))
                .thenReturn("INV-001");
        when(customerTransactionService.isIdempotencyKeyConstraintViolation(any())).thenReturn(true);
        when(transactionRepository.findByIdempotencyKey("idem-race"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(stored));
        when(transactionRepository.existsByTransferReference("INV-001")).thenReturn(false);
        when(customerTransactionDetailRepository.findByAccountNumberForUpdate("ACC001")).thenReturn(Optional.of(source));
        when(customerTransactionDetailRepository.findByAccountNumberForUpdate("ACC002")).thenReturn(Optional.of(destination));
        when(transactionRepository.saveAndFlush(any(TransferTransaction.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint \"transfer_transactions_idempotency_key_key\""));

        IdempotentResult<TransferResponse> result = transferService.transfer(request, "idem-race");

        assertThat(result.replayed()).isTrue();
        assertThat(result.payload().transferReference()).isEqualTo("INV-001");
    }

    @Test
    void shouldRejectTransferConflictWhenConcurrentIdempotencyKeyMapsToDifferentPayload() {
        TransferRequest request = new TransferRequest("acc001", "acc002", new BigDecimal("1000.00"), " inv-001 ");
        TransferTransaction stored = new TransferTransaction();
        stored.setTransferReference("OTHER");
        stored.setIdempotencyKey("idem-race-conflict");
        stored.setSourceAccountNumber("ACC001");
        stored.setDestinationAccountNumber("ACC003");
        stored.setAmount(new BigDecimal("1.00"));

        when(customerTransactionService.normalizeIdempotencyKey("idem-race-conflict")).thenReturn("idem-race-conflict");
        when(customerTransactionService.normalizeAccountNumber("acc001")).thenReturn("ACC001");
        when(customerTransactionService.normalizeAccountNumber("acc002")).thenReturn("ACC002");
        when(customerTransactionService.normalizeReference(" inv-001 ", "Transfer reference is required"))
                .thenReturn("INV-001");
        when(customerTransactionService.isIdempotencyKeyConstraintViolation(any())).thenReturn(true);
        when(transactionRepository.findByIdempotencyKey("idem-race-conflict"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(stored));
        when(transactionRepository.existsByTransferReference("INV-001")).thenReturn(false);
        when(customerTransactionDetailRepository.findByAccountNumberForUpdate("ACC001")).thenReturn(Optional.of(source));
        when(customerTransactionDetailRepository.findByAccountNumberForUpdate("ACC002")).thenReturn(Optional.of(destination));
        when(transactionRepository.saveAndFlush(any(TransferTransaction.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint \"transfer_transactions_idempotency_key_key\""));

        assertThatThrownBy(() -> transferService.transfer(request, "idem-race-conflict"))
                .isInstanceOf(IdempotencyConflictException.class)
                .hasMessage("Idempotency key was already used for a different transfer request");
    }

    @Test
    void shouldReplayExactIdempotentTransferWithoutMovingBalancesAgain() {
        TransferRequest request = new TransferRequest("acc001", "acc002", new BigDecimal("1000.00"), " inv-001 ");
        TransferTransaction transaction = new TransferTransaction();
        transaction.setTransferReference("INV-001");
        transaction.setIdempotencyKey("idem-1");
        transaction.setSourceAccountNumber("ACC001");
        transaction.setDestinationAccountNumber("ACC002");
        transaction.setAmount(new BigDecimal("1000.00"));
        transaction.setSourceBalanceAfterTransfer(new BigDecimal("4000.00"));
        transaction.setDestinationBalanceAfterTransfer(new BigDecimal("3000.00"));
        transaction.setStatus(TransactionStatus.SUCCESS);
        transaction.setCreatedAt(LocalDateTime.now());

        when(customerTransactionService.normalizeIdempotencyKey("idem-1")).thenReturn("idem-1");
        when(customerTransactionService.normalizeAccountNumber("acc001")).thenReturn("ACC001");
        when(customerTransactionService.normalizeAccountNumber("acc002")).thenReturn("ACC002");
        when(customerTransactionService.normalizeReference(" inv-001 ", "Transfer reference is required"))
                .thenReturn("INV-001");
        when(transactionRepository.findByIdempotencyKey("idem-1")).thenReturn(Optional.of(transaction));

        IdempotentResult<TransferResponse> result = transferService.transfer(request, "idem-1");

        assertThat(result.replayed()).isTrue();
        verify(customerTransactionDetailRepository, never()).findByAccountNumberForUpdate(any());
        verify(transactionRepository, never()).saveAndFlush(any());
    }

    @Test
    void shouldRejectIdempotencyConflictForDifferentTransferPayload() {
        TransferRequest request = new TransferRequest("ACC001", "ACC002", new BigDecimal("1000.00"), "INV-002");
        TransferTransaction transaction = new TransferTransaction();
        transaction.setTransferReference("INV-ORIGINAL");
        transaction.setIdempotencyKey("idem-2");
        transaction.setSourceAccountNumber("ACC001");
        transaction.setDestinationAccountNumber("ACC002");
        transaction.setAmount(new BigDecimal("1000.00"));

        when(customerTransactionService.normalizeIdempotencyKey("idem-2")).thenReturn("idem-2");
        when(customerTransactionService.normalizeAccountNumber("ACC001")).thenReturn("ACC001");
        when(customerTransactionService.normalizeAccountNumber("ACC002")).thenReturn("ACC002");
        when(customerTransactionService.normalizeReference("INV-002", "Transfer reference is required"))
                .thenReturn("INV-002");
        when(transactionRepository.findByIdempotencyKey("idem-2")).thenReturn(Optional.of(transaction));

        assertThatThrownBy(() -> transferService.transfer(request, "idem-2"))
                .isInstanceOf(IdempotencyConflictException.class)
                .hasMessage("Idempotency key was already used for a different transfer request");
    }

    @Test
    void shouldRejectInsufficientFunds() {
        TransferRequest request = new TransferRequest("ACC001", "ACC002", new BigDecimal("6000.00"), "INV-003");

        when(customerTransactionService.normalizeIdempotencyKey("idem-3")).thenReturn("idem-3");
        when(customerTransactionService.normalizeAccountNumber("ACC001")).thenReturn("ACC001");
        when(customerTransactionService.normalizeAccountNumber("ACC002")).thenReturn("ACC002");
        when(customerTransactionService.normalizeReference("INV-003", "Transfer reference is required"))
                .thenReturn("INV-003");
        when(transactionRepository.findByIdempotencyKey("idem-3")).thenReturn(Optional.empty());
        when(transactionRepository.existsByTransferReference("INV-003")).thenReturn(false);
        when(customerTransactionDetailRepository.findByAccountNumberForUpdate("ACC001")).thenReturn(Optional.of(source));
        when(customerTransactionDetailRepository.findByAccountNumberForUpdate("ACC002")).thenReturn(Optional.of(destination));

        assertThatThrownBy(() -> transferService.transfer(request, "idem-3"))
                .isInstanceOf(InsufficientFundsException.class)
                .hasMessage("Insufficient funds");
    }

    @Test
    void shouldRejectSameSourceAndDestination() {
        TransferRequest request = new TransferRequest("ACC001", "ACC001", new BigDecimal("100.00"), "INV-004");

        when(customerTransactionService.normalizeIdempotencyKey("idem-4")).thenReturn("idem-4");
        when(customerTransactionService.normalizeAccountNumber("ACC001")).thenReturn("ACC001");
        when(customerTransactionService.normalizeReference("INV-004", "Transfer reference is required"))
                .thenReturn("INV-004");
        when(transactionRepository.findByIdempotencyKey("idem-4")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transferService.transfer(request, "idem-4"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessage("Source and destination accounts must be different");
    }

    @Test
    void shouldRejectMissingSourceAccount() {
        TransferRequest request = new TransferRequest("ACC001", "ACC002", new BigDecimal("100.00"), "INV-005");

        when(customerTransactionService.normalizeIdempotencyKey("idem-5")).thenReturn("idem-5");
        when(customerTransactionService.normalizeAccountNumber("ACC001")).thenReturn("ACC001");
        when(customerTransactionService.normalizeAccountNumber("ACC002")).thenReturn("ACC002");
        when(customerTransactionService.normalizeReference("INV-005", "Transfer reference is required"))
                .thenReturn("INV-005");
        when(transactionRepository.findByIdempotencyKey("idem-5")).thenReturn(Optional.empty());
        when(transactionRepository.existsByTransferReference("INV-005")).thenReturn(false);
        when(customerTransactionDetailRepository.findByAccountNumberForUpdate("ACC001")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transferService.transfer(request, "idem-5"))
                .isInstanceOf(AccountNotFoundException.class)
                .hasMessage("Source account not found");
    }

    @Test
    void shouldRejectMissingDestinationAccount() {
        TransferRequest request = new TransferRequest("ACC001", "ACC002", new BigDecimal("100.00"), "INV-006");

        when(customerTransactionService.normalizeIdempotencyKey("idem-6")).thenReturn("idem-6");
        when(customerTransactionService.normalizeAccountNumber("ACC001")).thenReturn("ACC001");
        when(customerTransactionService.normalizeAccountNumber("ACC002")).thenReturn("ACC002");
        when(customerTransactionService.normalizeReference("INV-006", "Transfer reference is required"))
                .thenReturn("INV-006");
        when(transactionRepository.findByIdempotencyKey("idem-6")).thenReturn(Optional.empty());
        when(transactionRepository.existsByTransferReference("INV-006")).thenReturn(false);
        when(customerTransactionDetailRepository.findByAccountNumberForUpdate("ACC001")).thenReturn(Optional.of(source));
        when(customerTransactionDetailRepository.findByAccountNumberForUpdate("ACC002")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transferService.transfer(request, "idem-6"))
                .isInstanceOf(AccountNotFoundException.class)
                .hasMessage("Destination account not found");
    }

    @Test
    void shouldRejectNonPositiveAmount() {
        TransferRequest request = new TransferRequest("ACC001", "ACC002", BigDecimal.ZERO, "INV-007");

        when(customerTransactionService.normalizeIdempotencyKey("idem-7")).thenReturn("idem-7");
        when(customerTransactionService.normalizeAccountNumber("ACC001")).thenReturn("ACC001");
        when(customerTransactionService.normalizeAccountNumber("ACC002")).thenReturn("ACC002");

        assertThatThrownBy(() -> transferService.transfer(request, "idem-7"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessage("Amount must be greater than zero");
    }

    @Test
    void shouldRejectOverscaleAmount() {
        TransferRequest request = new TransferRequest("ACC001", "ACC002", new BigDecimal("100.999"), "INV-007A");

        when(customerTransactionService.normalizeIdempotencyKey("idem-7a")).thenReturn("idem-7a");
        when(customerTransactionService.normalizeAccountNumber("ACC001")).thenReturn("ACC001");
        when(customerTransactionService.normalizeAccountNumber("ACC002")).thenReturn("ACC002");

        assertThatThrownBy(() -> transferService.transfer(request, "idem-7a"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessage("Amount must have at most 2 decimal places");
        verify(transactionRepository, never()).findByIdempotencyKey(any());
    }

    @Test
    void shouldPropagateLockConflicts() {
        TransferRequest request = new TransferRequest("ACC001", "ACC002", new BigDecimal("1000.00"), "INV-008");

        when(customerTransactionService.normalizeIdempotencyKey("idem-8")).thenReturn("idem-8");
        when(customerTransactionService.normalizeAccountNumber("ACC001")).thenReturn("ACC001");
        when(customerTransactionService.normalizeAccountNumber("ACC002")).thenReturn("ACC002");
        when(customerTransactionService.normalizeReference("INV-008", "Transfer reference is required"))
                .thenReturn("INV-008");
        when(transactionRepository.findByIdempotencyKey("idem-8")).thenReturn(Optional.empty());
        when(transactionRepository.existsByTransferReference("INV-008")).thenReturn(false);
        doThrow(new CannotAcquireLockException("busy"))
                .when(customerTransactionDetailRepository).findByAccountNumberForUpdate("ACC001");

        assertThatThrownBy(() -> transferService.transfer(request, "idem-8"))
                .isInstanceOf(CannotAcquireLockException.class);
    }

    @Test
    void shouldRejectDuplicateTransferReference() {
        TransferRequest request = new TransferRequest("ACC001", "ACC002", new BigDecimal("1000.00"), "INV-009");

        when(customerTransactionService.normalizeIdempotencyKey("idem-9")).thenReturn("idem-9");
        when(customerTransactionService.normalizeAccountNumber("ACC001")).thenReturn("ACC001");
        when(customerTransactionService.normalizeAccountNumber("ACC002")).thenReturn("ACC002");
        when(customerTransactionService.normalizeReference("INV-009", "Transfer reference is required"))
                .thenReturn("INV-009");
        when(transactionRepository.findByIdempotencyKey("idem-9")).thenReturn(Optional.empty());
        when(transactionRepository.existsByTransferReference("INV-009")).thenReturn(true);

        assertThatThrownBy(() -> transferService.transfer(request, "idem-9"))
                .isInstanceOf(DuplicateTransferReferenceException.class)
                .hasMessage("Transfer reference already exists");
    }

    @Test
    void shouldRejectMissingIdempotencyKey() {
        TransferRequest request = new TransferRequest("ACC001", "ACC002", new BigDecimal("1000.00"), "INV-010");

        when(customerTransactionService.normalizeIdempotencyKey("   "))
                .thenThrow(new MissingIdempotencyKeyException("Idempotency-Key header is required"));

        assertThatThrownBy(() -> transferService.transfer(request, "   "))
                .isInstanceOf(MissingIdempotencyKeyException.class)
                .hasMessage("Idempotency-Key header is required");
    }
}
