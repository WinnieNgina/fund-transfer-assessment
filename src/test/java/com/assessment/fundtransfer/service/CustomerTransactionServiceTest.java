package com.assessment.fundtransfer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

@ExtendWith(MockitoExtension.class)
class CustomerTransactionServiceTest {

    @Mock
    private CustomerTransactionDetailRepository customerTransactionDetailRepository;

    private CustomerTransactionService customerTransactionService;

    @BeforeEach
    void setUp() {
        TransactionOperations transactionOperations = new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(null);
            }
        };
        customerTransactionService = new CustomerTransactionService(
                customerTransactionDetailRepository,
                transactionOperations
        );
    }

    @Test
    void shouldSaveCustomerTransactionDetails() {
        CustomerTransactionRequest request = new CustomerTransactionRequest(
                "CUST-001",
                "Alice",
                "acc001",
                " txn-001 ",
                new BigDecimal("5000.00")
        );
        CustomerTransactionDetail transactionDetail = new CustomerTransactionDetail();
        transactionDetail.setId(1L);
        transactionDetail.setCustomerId("CUST-001");
        transactionDetail.setCustomerName("Alice");
        transactionDetail.setAccountNumber("ACC001");
        transactionDetail.setTransactionReference("TXN-001");
        transactionDetail.setIdempotencyKey("idem-1");
        transactionDetail.setTransactionType(TransactionType.CREDIT);
        transactionDetail.setTransactionAmount(new BigDecimal("5000.00"));
        transactionDetail.setCurrentBalance(new BigDecimal("5000.00"));
        transactionDetail.setCreatedAt(LocalDateTime.now());

        when(customerTransactionDetailRepository.findByIdempotencyKey("idem-1")).thenReturn(Optional.empty());
        when(customerTransactionDetailRepository.existsByAccountNumber("ACC001")).thenReturn(false);
        when(customerTransactionDetailRepository.existsByTransactionReference("TXN-001")).thenReturn(false);
        when(customerTransactionDetailRepository.saveAndFlush(any(CustomerTransactionDetail.class))).thenReturn(transactionDetail);

        IdempotentResult<CustomerTransactionResponse> result =
                customerTransactionService.saveCustomerTransactionDetails(request, "idem-1");

        assertThat(result.replayed()).isFalse();
        assertThat(result.payload().customerId()).isEqualTo("CUST-001");
        assertThat(result.payload().accountNumber()).isEqualTo("ACC001");
        assertThat(result.payload().transactionReference()).isEqualTo("TXN-001");
        assertThat(result.payload().currentBalance()).isEqualByComparingTo("5000.00");
    }

    @Test
    void shouldReplayCustomerTransactionWhenConcurrentIdenticalInsertClaimsIdempotencyKey() {
        CustomerTransactionRequest request = new CustomerTransactionRequest(
                "CUST-001",
                "Alice",
                "ACC001",
                "TXN-001",
                new BigDecimal("5000.00")
        );
        CustomerTransactionDetail stored = new CustomerTransactionDetail();
        stored.setId(1L);
        stored.setCustomerId("CUST-001");
        stored.setCustomerName("Alice");
        stored.setAccountNumber("ACC001");
        stored.setTransactionReference("TXN-001");
        stored.setIdempotencyKey("idem-race");
        stored.setTransactionType(TransactionType.CREDIT);
        stored.setTransactionAmount(new BigDecimal("5000.00"));
        stored.setCurrentBalance(new BigDecimal("5000.00"));
        stored.setCreatedAt(LocalDateTime.now());

        when(customerTransactionDetailRepository.findByIdempotencyKey("idem-race"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(stored));
        when(customerTransactionDetailRepository.existsByAccountNumber("ACC001")).thenReturn(false);
        when(customerTransactionDetailRepository.existsByTransactionReference("TXN-001")).thenReturn(false);
        when(customerTransactionDetailRepository.saveAndFlush(any(CustomerTransactionDetail.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint \"transaction_logs_idempotency_key_key\""));

        IdempotentResult<CustomerTransactionResponse> result =
                customerTransactionService.saveCustomerTransactionDetails(request, "idem-race");

        assertThat(result.replayed()).isTrue();
        assertThat(result.payload().transactionReference()).isEqualTo("TXN-001");
    }

    @Test
    void shouldRejectCustomerTransactionConflictWhenConcurrentIdempotencyKeyMapsToDifferentPayload() {
        CustomerTransactionRequest request = new CustomerTransactionRequest(
                "CUST-001",
                "Alice",
                "ACC001",
                "TXN-001",
                new BigDecimal("5000.00")
        );
        CustomerTransactionDetail stored = new CustomerTransactionDetail();
        stored.setCustomerId("CUST-999");
        stored.setCustomerName("Mallory");
        stored.setAccountNumber("ACC999");
        stored.setTransactionReference("TXN-999");
        stored.setIdempotencyKey("idem-race-conflict");
        stored.setTransactionType(TransactionType.CREDIT);
        stored.setTransactionAmount(new BigDecimal("1.00"));
        stored.setCurrentBalance(new BigDecimal("1.00"));

        when(customerTransactionDetailRepository.findByIdempotencyKey("idem-race-conflict"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(stored));
        when(customerTransactionDetailRepository.existsByAccountNumber("ACC001")).thenReturn(false);
        when(customerTransactionDetailRepository.existsByTransactionReference("TXN-001")).thenReturn(false);
        when(customerTransactionDetailRepository.saveAndFlush(any(CustomerTransactionDetail.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint \"transaction_logs_idempotency_key_key\""));

        assertThatThrownBy(() -> customerTransactionService.saveCustomerTransactionDetails(request, "idem-race-conflict"))
                .isInstanceOf(IdempotencyConflictException.class)
                .hasMessage("Idempotency key was already used for a different customer transaction request");
    }

    @Test
    void shouldReplayExistingCustomerTransactionDetailsByIdempotencyKey() {
        CustomerTransactionRequest request = new CustomerTransactionRequest(
                "CUST-001",
                "Alice",
                "ACC001",
                "TXN-001",
                new BigDecimal("5000.00")
        );
        CustomerTransactionDetail transactionDetail = new CustomerTransactionDetail();
        transactionDetail.setId(1L);
        transactionDetail.setCustomerId("CUST-001");
        transactionDetail.setCustomerName("Alice");
        transactionDetail.setAccountNumber("ACC001");
        transactionDetail.setTransactionReference("TXN-001");
        transactionDetail.setIdempotencyKey("idem-1");
        transactionDetail.setTransactionType(TransactionType.CREDIT);
        transactionDetail.setTransactionAmount(new BigDecimal("5000.00"));
        transactionDetail.setCurrentBalance(new BigDecimal("5000.00"));
        transactionDetail.setCreatedAt(LocalDateTime.now());

        when(customerTransactionDetailRepository.findByIdempotencyKey("idem-1")).thenReturn(Optional.of(transactionDetail));

        IdempotentResult<CustomerTransactionResponse> result =
                customerTransactionService.saveCustomerTransactionDetails(request, "idem-1");

        assertThat(result.replayed()).isTrue();
        assertThat(result.payload().transactionReference()).isEqualTo("TXN-001");
        verify(customerTransactionDetailRepository).findByIdempotencyKey("idem-1");
    }

    @Test
    void shouldRejectIdempotencyConflictForDifferentCustomerTransactionPayload() {
        CustomerTransactionRequest request = new CustomerTransactionRequest(
                "CUST-002",
                "Bob",
                "ACC002",
                "TXN-002",
                new BigDecimal("1500.00")
        );
        CustomerTransactionDetail transactionDetail = new CustomerTransactionDetail();
        transactionDetail.setId(1L);
        transactionDetail.setCustomerId("CUST-001");
        transactionDetail.setCustomerName("Alice");
        transactionDetail.setAccountNumber("ACC001");
        transactionDetail.setTransactionReference("TXN-001");
        transactionDetail.setIdempotencyKey("idem-1");
        transactionDetail.setTransactionType(TransactionType.CREDIT);
        transactionDetail.setTransactionAmount(new BigDecimal("5000.00"));
        transactionDetail.setCurrentBalance(new BigDecimal("5000.00"));

        when(customerTransactionDetailRepository.findByIdempotencyKey("idem-1")).thenReturn(Optional.of(transactionDetail));

        assertThatThrownBy(() -> customerTransactionService.saveCustomerTransactionDetails(request, "idem-1"))
                .isInstanceOf(IdempotencyConflictException.class)
                .hasMessage("Idempotency key was already used for a different customer transaction request");
    }

    @Test
    void shouldRejectDuplicateAccountNumber() {
        CustomerTransactionRequest request = new CustomerTransactionRequest(
                "CUST-001",
                "Alice",
                "ACC001",
                "TXN-001",
                new BigDecimal("5000.00")
        );
        when(customerTransactionDetailRepository.findByIdempotencyKey("idem-2")).thenReturn(Optional.empty());
        when(customerTransactionDetailRepository.existsByAccountNumber("ACC001")).thenReturn(true);

        assertThatThrownBy(() -> customerTransactionService.saveCustomerTransactionDetails(request, "idem-2"))
                .isInstanceOf(DuplicateAccountException.class)
                .hasMessage("Account number already exists");
    }

    @Test
    void shouldRejectDuplicateTransactionReference() {
        CustomerTransactionRequest request = new CustomerTransactionRequest(
                "CUST-001",
                "Alice",
                "ACC001",
                "TXN-001",
                new BigDecimal("5000.00")
        );
        when(customerTransactionDetailRepository.findByIdempotencyKey("idem-3")).thenReturn(Optional.empty());
        when(customerTransactionDetailRepository.existsByAccountNumber("ACC001")).thenReturn(false);
        when(customerTransactionDetailRepository.existsByTransactionReference("TXN-001")).thenReturn(true);

        assertThatThrownBy(() -> customerTransactionService.saveCustomerTransactionDetails(request, "idem-3"))
                .isInstanceOf(DuplicateTransactionReferenceException.class)
                .hasMessage("Transaction reference already exists");
    }

    @Test
    void shouldRejectMissingIdempotencyKey() {
        CustomerTransactionRequest request = new CustomerTransactionRequest(
                "CUST-001",
                "Alice",
                "ACC001",
                "TXN-001",
                new BigDecimal("5000.00")
        );

        assertThatThrownBy(() -> customerTransactionService.saveCustomerTransactionDetails(request, "   "))
                .isInstanceOf(MissingIdempotencyKeyException.class)
                .hasMessage("Idempotency-Key header is required");
    }

    @Test
    void shouldPersistOpeningBalanceFromTransactionAmount() {
        CustomerTransactionRequest request = new CustomerTransactionRequest(
                "CUST-002",
                "Bob",
                "ACC002",
                "TXN-002",
                new BigDecimal("2000.00")
        );
        CustomerTransactionDetail saved = new CustomerTransactionDetail();
        saved.setId(2L);
        saved.setCustomerId("CUST-002");
        saved.setCustomerName("Bob");
        saved.setAccountNumber("ACC002");
        saved.setTransactionReference("TXN-002");
        saved.setIdempotencyKey("idem-4");
        saved.setTransactionType(TransactionType.CREDIT);
        saved.setTransactionAmount(new BigDecimal("2000.00"));
        saved.setCurrentBalance(new BigDecimal("2000.00"));
        saved.setCreatedAt(LocalDateTime.now());

        when(customerTransactionDetailRepository.findByIdempotencyKey("idem-4")).thenReturn(Optional.empty());
        when(customerTransactionDetailRepository.existsByAccountNumber("ACC002")).thenReturn(false);
        when(customerTransactionDetailRepository.existsByTransactionReference("TXN-002")).thenReturn(false);
        when(customerTransactionDetailRepository.saveAndFlush(any(CustomerTransactionDetail.class))).thenReturn(saved);

        IdempotentResult<CustomerTransactionResponse> result =
                customerTransactionService.saveCustomerTransactionDetails(request, "idem-4");

        assertThat(result.replayed()).isFalse();
        assertThat(result.payload().currentBalance()).isEqualByComparingTo("2000.00");
    }

    @Test
    void shouldReturnCustomerBalance() {
        CustomerTransactionDetail transactionDetail = new CustomerTransactionDetail();
        transactionDetail.setCustomerId("CUST-001");
        transactionDetail.setCustomerName("Alice");
        transactionDetail.setAccountNumber("ACC001");
        transactionDetail.setTransactionType(TransactionType.CREDIT);
        transactionDetail.setCurrentBalance(new BigDecimal("4000.00"));
        transactionDetail.setUpdatedAt(LocalDateTime.now());
        when(customerTransactionDetailRepository.findByAccountNumber("ACC001")).thenReturn(Optional.of(transactionDetail));

        BalanceResponse response = customerTransactionService.getBalance("acc001");

        assertThat(response.customerId()).isEqualTo("CUST-001");
        assertThat(response.currentBalance()).isEqualByComparingTo("4000.00");
    }

    @Test
    void shouldThrowWhenAccountDoesNotExist() {
        when(customerTransactionDetailRepository.findByAccountNumber("ACC001")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> customerTransactionService.getBalance("acc001"))
                .isInstanceOf(AccountNotFoundException.class)
                .hasMessage("Account not found");
    }

    @Test
    void shouldRejectNullFundingAmountAtServiceLayer() {
        CustomerTransactionRequest request = new CustomerTransactionRequest(
                "CUST-001",
                "Alice",
                "ACC001",
                "TXN-001",
                null
        );

        assertThatThrownBy(() -> customerTransactionService.saveCustomerTransactionDetails(request, "idem-null"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessage("Transaction amount is required");
        verify(customerTransactionDetailRepository, never()).findByIdempotencyKey(any());
    }

    @Test
    void shouldRejectNegativeFundingAmountAtServiceLayer() {
        CustomerTransactionRequest request = new CustomerTransactionRequest(
                "CUST-001",
                "Alice",
                "ACC001",
                "TXN-001",
                new BigDecimal("-0.01")
        );

        assertThatThrownBy(() -> customerTransactionService.saveCustomerTransactionDetails(request, "idem-negative"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessage("Transaction amount cannot be negative");
    }

    @Test
    void shouldRejectOverscaleFundingAmountAtServiceLayer() {
        CustomerTransactionRequest request = new CustomerTransactionRequest(
                "CUST-001",
                "Alice",
                "ACC001",
                "TXN-001",
                new BigDecimal("10.999")
        );

        assertThatThrownBy(() -> customerTransactionService.saveCustomerTransactionDetails(request, "idem-scale"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessage("Transaction amount must have at most 2 decimal places");
    }
}
