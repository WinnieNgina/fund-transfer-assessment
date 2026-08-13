package com.assessment.fundtransfer.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.assessment.fundtransfer.dto.request.CustomerTransactionRequest;
import com.assessment.fundtransfer.dto.response.BalanceResponse;
import com.assessment.fundtransfer.dto.response.CustomerTransactionResponse;
import com.assessment.fundtransfer.exception.AccountNotFoundException;
import com.assessment.fundtransfer.exception.DuplicateAccountException;
import com.assessment.fundtransfer.exception.DuplicateTransactionReferenceException;
import com.assessment.fundtransfer.exception.IdempotencyConflictException;
import com.assessment.fundtransfer.exception.MissingIdempotencyKeyException;
import com.assessment.fundtransfer.service.CustomerTransactionService;
import com.assessment.fundtransfer.service.IdempotentResult;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CustomerTransactionController.class)
class CustomerTransactionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CustomerTransactionService customerTransactionService;

    @Test
    void postCustomerTransactionsReturns201() throws Exception {
        CustomerTransactionResponse response = new CustomerTransactionResponse(
                1L,
                "CUST-001",
                "Alice",
                "ACC001",
                "TXN-001",
                new BigDecimal("5000.00"),
                new BigDecimal("5000.00"),
                LocalDateTime.now()
        );
        when(customerTransactionService.saveCustomerTransactionDetails(any(CustomerTransactionRequest.class), eq("idem-1")))
                .thenReturn(new IdempotentResult<>(response, false));

        mockMvc.perform(post("/api/v1/customer-accounts")
                        .header("Idempotency-Key", "idem-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "customerId": "CUST-001",
                                  "customerName": "Alice",
                                  "accountNumber": "ACC001",
                                  "transactionReference": "TXN-001",
                                  "transactionAmount": 5000.00
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Customer account created and funded successfully"))
                .andExpect(jsonPath("$.data.accountNumber").value("ACC001"))
                .andExpect(jsonPath("$.data.transactionReference").value("TXN-001"))
                .andExpect(jsonPath("$.data.currentBalance").value(5000.00))
                .andExpect(jsonPath("$.data.transactionType").doesNotExist())
                .andExpect(jsonPath("$.path").value("/api/v1/customer-accounts"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.errorCode").value(Matchers.nullValue()));
    }

    @Test
    void postCustomerTransactionsReturns200ForIdempotentReplay() throws Exception {
        CustomerTransactionResponse response = new CustomerTransactionResponse(
                1L,
                "CUST-001",
                "Alice",
                "ACC001",
                "TXN-001",
                new BigDecimal("5000.00"),
                new BigDecimal("5000.00"),
                LocalDateTime.now()
        );
        when(customerTransactionService.saveCustomerTransactionDetails(any(CustomerTransactionRequest.class), eq("idem-1")))
                .thenReturn(new IdempotentResult<>(response, true));

        mockMvc.perform(post("/api/v1/customer-accounts")
                        .header("Idempotency-Key", "idem-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "customerId": "CUST-001",
                                  "customerName": "Alice",
                                  "accountNumber": "ACC001",
                                  "transactionReference": "TXN-001",
                                  "transactionAmount": 5000.00
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message")
                        .value("Customer account funding already processed for this idempotency key"));
    }

    @Test
    void postCustomerTransactionsRejectsMissingIdempotencyKey() throws Exception {
        when(customerTransactionService.saveCustomerTransactionDetails(any(CustomerTransactionRequest.class), eq((String) null)))
                .thenThrow(new MissingIdempotencyKeyException("Idempotency-Key header is required"));

        mockMvc.perform(post("/api/v1/customer-accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "customerId": "CUST-001",
                                  "customerName": "Alice",
                                  "accountNumber": "ACC001",
                                  "transactionReference": "TXN-001",
                                  "transactionAmount": 5000.00
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Idempotency-Key header is required"))
                .andExpect(jsonPath("$.errorCode").value("MISSING_IDEMPOTENCY_KEY"));
    }

    @Test
    void postCustomerTransactionsRejectsInvalidInput() throws Exception {
        mockMvc.perform(post("/api/v1/customer-accounts")
                        .header("Idempotency-Key", "idem-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "customerId": "",
                                  "customerName": "",
                                  "accountNumber": "",
                                  "transactionReference": "",
                                  "transactionAmount": -1.00
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.path").value("/api/v1/customer-accounts"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.errors[*].field", Matchers.hasItems(
                        "customerId",
                        "customerName",
                        "accountNumber",
                        "transactionReference",
                        "transactionAmount"
                )))
                .andExpect(jsonPath("$.errors[?(@.field=='transactionAmount')].message",
                        Matchers.hasItem("Transaction amount cannot be negative")));
    }

    @Test
    void postCustomerTransactionsReturns409ForDuplicateAccount() throws Exception {
        when(customerTransactionService.saveCustomerTransactionDetails(any(CustomerTransactionRequest.class), eq("idem-3")))
                .thenThrow(new DuplicateAccountException("Account number already exists"));

        mockMvc.perform(post("/api/v1/customer-accounts")
                        .header("Idempotency-Key", "idem-3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "customerId": "CUST-001",
                                  "customerName": "Alice",
                                  "accountNumber": "ACC001",
                                  "transactionReference": "TXN-001",
                                  "transactionAmount": 5000.00
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Account number already exists"))
                .andExpect(jsonPath("$.errorCode").value("DUPLICATE_ACCOUNT"))
                .andExpect(jsonPath("$.path").value("/api/v1/customer-accounts"));
    }

    @Test
    void postCustomerTransactionsReturns409ForDuplicateTransactionReference() throws Exception {
        when(customerTransactionService.saveCustomerTransactionDetails(any(CustomerTransactionRequest.class), eq("idem-4")))
                .thenThrow(new DuplicateTransactionReferenceException("Transaction reference already exists"));

        mockMvc.perform(post("/api/v1/customer-accounts")
                        .header("Idempotency-Key", "idem-4")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "customerId": "CUST-001",
                                  "customerName": "Alice",
                                  "accountNumber": "ACC001",
                                  "transactionReference": "TXN-001",
                                  "transactionAmount": 5000.00
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Transaction reference already exists"))
                .andExpect(jsonPath("$.errorCode").value("DUPLICATE_TRANSACTION_REFERENCE"));
    }

    @Test
    void postCustomerTransactionsReturns409ForIdempotencyConflict() throws Exception {
        when(customerTransactionService.saveCustomerTransactionDetails(any(CustomerTransactionRequest.class), eq("idem-5")))
                .thenThrow(new IdempotencyConflictException(
                        "Idempotency key was already used for a different customer transaction request"
                ));

        mockMvc.perform(post("/api/v1/customer-accounts")
                        .header("Idempotency-Key", "idem-5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "customerId": "CUST-002",
                                  "customerName": "Bob",
                                  "accountNumber": "ACC002",
                                  "transactionReference": "TXN-002",
                                  "transactionAmount": 1000.00
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message")
                        .value("Idempotency key has already been used with a different request payload"))
                .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_KEY_CONFLICT"));
    }

    @Test
    void getBalanceReturns200() throws Exception {
        when(customerTransactionService.getBalance("ACC001"))
                .thenReturn(new BalanceResponse(
                        "CUST-001",
                        "Alice",
                        "ACC001",
                        new BigDecimal("4000.00"),
                        LocalDateTime.now()
                ));

        mockMvc.perform(get("/api/v1/customer-accounts/ACC001/balance"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.path").value("/api/v1/customer-accounts/ACC001/balance"))
                .andExpect(jsonPath("$.data.customerId").value("CUST-001"))
                .andExpect(jsonPath("$.data.currentBalance").value(4000.00));
    }

    @Test
    void getBalanceReturns404ForMissingAccount() throws Exception {
        when(customerTransactionService.getBalance("UNKNOWN"))
                .thenThrow(new AccountNotFoundException("Account not found"));

        mockMvc.perform(get("/api/v1/customer-accounts/UNKNOWN/balance"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Account not found"))
                .andExpect(jsonPath("$.errorCode").value("ACCOUNT_NOT_FOUND"))
                .andExpect(jsonPath("$.path").value("/api/v1/customer-accounts/UNKNOWN/balance"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }
}
