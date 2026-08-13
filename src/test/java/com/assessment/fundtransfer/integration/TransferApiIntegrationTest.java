package com.assessment.fundtransfer.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.assessment.fundtransfer.TestcontainersConfiguration;
import com.assessment.fundtransfer.entity.CustomerTransactionDetail;
import com.assessment.fundtransfer.entity.TransactionStatus;
import com.assessment.fundtransfer.entity.TransactionType;
import com.assessment.fundtransfer.entity.TransferTransaction;
import com.assessment.fundtransfer.repository.CustomerTransactionDetailRepository;
import com.assessment.fundtransfer.repository.TransferTransactionRepository;
import com.assessment.fundtransfer.web.CorrelationIdFilter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class TransferApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CustomerTransactionDetailRepository customerTransactionDetailRepository;

    @Autowired
    private TransferTransactionRepository transferTransactionRepository;

    @BeforeEach
    void setUp() {
        transferTransactionRepository.deleteAll();
        customerTransactionDetailRepository.deleteAll();
    }

    @Test
    void shouldTransferFundsAndPersistTransferRecord() throws Exception {
        saveCustomerTransaction("CUST-001", "Alice", "ACC001", "TXN-001", TransactionType.CREDIT, "idem-ct-1", "5000.00", "5000.00");
        saveCustomerTransaction("CUST-002", "Bob", "ACC002", "TXN-002", TransactionType.CREDIT, "idem-ct-2", "2000.00", "2000.00");

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "idem-tr-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceAccountNumber": "ACC001",
                                  "destinationAccountNumber": "ACC002",
                                  "amount": 1000.00,
                                  "transferReference": "TRF-001"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.transferReference").value("TRF-001"))
                .andExpect(jsonPath("$.data.sourceBalanceAfterTransfer").value(4000.00))
                .andExpect(jsonPath("$.data.destinationBalanceAfterTransfer").value(3000.00));

        CustomerTransactionDetail source = customerTransactionDetailRepository.findByAccountNumber("ACC001").orElseThrow();
        CustomerTransactionDetail destination = customerTransactionDetailRepository.findByAccountNumber("ACC002").orElseThrow();
        TransferTransaction transfer = transferTransactionRepository.findByIdempotencyKey("idem-tr-1").orElseThrow();
        assertThat(source.getCurrentBalance()).isEqualByComparingTo("4000.00");
        assertThat(destination.getCurrentBalance()).isEqualByComparingTo("3000.00");
        assertThat(transfer.getTransferReference()).isEqualTo("TRF-001");
    }

    @Test
    void shouldReplayExactTransferByIdempotencyKey() throws Exception {
        saveCustomerTransaction("CUST-001", "Alice", "ACC001", "TXN-001", TransactionType.CREDIT, "idem-ct-1", "5000.00", "5000.00");
        saveCustomerTransaction("CUST-002", "Bob", "ACC002", "TXN-002", TransactionType.CREDIT, "idem-ct-2", "2000.00", "2000.00");

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "idem-tr-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceAccountNumber": "ACC001",
                                  "destinationAccountNumber": "ACC002",
                                  "amount": 1000.00,
                                  "transferReference": "TRF-002"
                                }
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "idem-tr-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceAccountNumber": "ACC001",
                                  "destinationAccountNumber": "ACC002",
                                  "amount": 1000.00,
                                  "transferReference": "TRF-002"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Funds transfer already processed for this idempotency key"));

        assertThat(transferTransactionRepository.count()).isEqualTo(1L);
        assertThat(customerTransactionDetailRepository.findByAccountNumber("ACC001").orElseThrow().getCurrentBalance())
                .isEqualByComparingTo("4000.00");
    }

    @Test
    void shouldRejectReusedIdempotencyKeyWithDifferentTransferPayload() throws Exception {
        saveCustomerTransaction("CUST-001", "Alice", "ACC001", "TXN-001", TransactionType.CREDIT, "idem-ct-1", "5000.00", "5000.00");
        saveCustomerTransaction("CUST-002", "Bob", "ACC002", "TXN-002", TransactionType.CREDIT, "idem-ct-2", "2000.00", "2000.00");

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "idem-tr-3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceAccountNumber": "ACC001",
                                  "destinationAccountNumber": "ACC002",
                                  "amount": 1000.00,
                                  "transferReference": "TRF-003"
                                }
                                """))
                .andExpect(status().isCreated());

        MvcResult result = mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "idem-tr-3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceAccountNumber": "ACC001",
                                  "destinationAccountNumber": "ACC002",
                                  "amount": 500.00,
                                  "transferReference": "TRF-004"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_KEY_CONFLICT"))
                .andReturn();

        assertErrorContract(result, "/api/v1/transfers");
    }

    @Test
    void shouldRejectMissingIdempotencyKey() throws Exception {
        saveCustomerTransaction("CUST-001", "Alice", "ACC001", "TXN-001", TransactionType.CREDIT, "idem-ct-1", "5000.00", "5000.00");
        saveCustomerTransaction("CUST-002", "Bob", "ACC002", "TXN-002", TransactionType.CREDIT, "idem-ct-2", "2000.00", "2000.00");

        MvcResult result = mockMvc.perform(post("/api/v1/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceAccountNumber": "ACC001",
                                  "destinationAccountNumber": "ACC002",
                                  "amount": 1000.00,
                                  "transferReference": "TRF-005"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("MISSING_IDEMPOTENCY_KEY"))
                .andReturn();

        assertErrorContract(result, "/api/v1/transfers");
    }

    @Test
    void shouldRejectOverscaleTransferAmount() throws Exception {
        saveCustomerTransaction("CUST-001", "Alice", "ACC001", "TXN-001", TransactionType.CREDIT, "idem-ct-1", "5000.00", "5000.00");
        saveCustomerTransaction("CUST-002", "Bob", "ACC002", "TXN-002", TransactionType.CREDIT, "idem-ct-2", "2000.00", "2000.00");

        MvcResult result = mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "idem-tr-scale")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceAccountNumber": "ACC001",
                                  "destinationAccountNumber": "ACC002",
                                  "amount": 10.999,
                                  "transferReference": "TRF-SCALE"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("amount"))
                .andExpect(jsonPath("$.errors[0].message").value("Amount must have at most 2 decimal places"))
                .andReturn();

        assertErrorContract(result, "/api/v1/transfers");
    }

    @Test
    void shouldRejectInsufficientFundsWithoutPartialUpdate() throws Exception {
        saveCustomerTransaction("CUST-001", "Alice", "ACC001", "TXN-001", TransactionType.CREDIT, "idem-ct-1", "5000.00", "5000.00");
        saveCustomerTransaction("CUST-002", "Bob", "ACC002", "TXN-002", TransactionType.CREDIT, "idem-ct-2", "2000.00", "2000.00");

        MvcResult result = mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "idem-tr-4")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceAccountNumber": "ACC001",
                                  "destinationAccountNumber": "ACC002",
                                  "amount": 6000.00,
                                  "transferReference": "TRF-006"
                                }
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("INSUFFICIENT_FUNDS"))
                .andExpect(jsonPath("$.message").value("Insufficient funds in source account"))
                .andReturn();

        assertErrorContract(result, "/api/v1/transfers");

        CustomerTransactionDetail source = customerTransactionDetailRepository.findByAccountNumber("ACC001").orElseThrow();
        CustomerTransactionDetail destination = customerTransactionDetailRepository.findByAccountNumber("ACC002").orElseThrow();
        assertThat(source.getCurrentBalance()).isEqualByComparingTo("5000.00");
        assertThat(destination.getCurrentBalance()).isEqualByComparingTo("2000.00");
        assertThat(transferTransactionRepository.count()).isZero();
    }

    @Test
    void shouldRejectDuplicateTransferReference() throws Exception {
        saveCustomerTransaction("CUST-001", "Alice", "ACC001", "TXN-001", TransactionType.CREDIT, "idem-ct-1", "5000.00", "5000.00");
        saveCustomerTransaction("CUST-002", "Bob", "ACC002", "TXN-002", TransactionType.CREDIT, "idem-ct-2", "2000.00", "2000.00");
        TransferTransaction transferTransaction = new TransferTransaction();
        transferTransaction.setTransferReference("TRF-007");
        transferTransaction.setIdempotencyKey("idem-existing-transfer");
        transferTransaction.setSourceAccountNumber("ACC001");
        transferTransaction.setDestinationAccountNumber("ACC002");
        transferTransaction.setAmount(new BigDecimal("1000.00"));
        transferTransaction.setSourceBalanceAfterTransfer(new BigDecimal("4000.00"));
        transferTransaction.setDestinationBalanceAfterTransfer(new BigDecimal("3000.00"));
        transferTransaction.setStatus(TransactionStatus.SUCCESS);
        transferTransactionRepository.save(transferTransaction);

        MvcResult result = mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "idem-tr-5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceAccountNumber": "ACC001",
                                  "destinationAccountNumber": "ACC002",
                                  "amount": 1000.00,
                                  "transferReference": "TRF-007"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DUPLICATE_TRANSFER_REFERENCE"))
                .andReturn();

        assertErrorContract(result, "/api/v1/transfers");
    }

    @Test
    void shouldRejectMissingSourceAccount() throws Exception {
        saveCustomerTransaction("CUST-002", "Bob", "ACC002", "TXN-002", TransactionType.CREDIT, "idem-ct-2", "2000.00", "2000.00");

        MvcResult result = mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "idem-tr-6")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceAccountNumber": "ACC001",
                                  "destinationAccountNumber": "ACC002",
                                  "amount": 1000.00,
                                  "transferReference": "TRF-008"
                                }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("ACCOUNT_NOT_FOUND"))
                .andReturn();

        assertErrorContract(result, "/api/v1/transfers");
    }

    @Test
    void shouldReturnStructuredErrorForMalformedJsonAndPreserveCorrelationId() throws Exception {
        saveCustomerTransaction("CUST-001", "Alice", "ACC001", "TXN-001", TransactionType.CREDIT, "idem-ct-1", "5000.00", "5000.00");
        saveCustomerTransaction("CUST-002", "Bob", "ACC002", "TXN-002", TransactionType.CREDIT, "idem-ct-2", "2000.00", "2000.00");

        MvcResult result = mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "idem-tr-malformed")
                        .header(CorrelationIdFilter.HEADER_NAME, "corr-transfer-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceAccountNumber": "ACC001",
                                  "destinationAccountNumber": "ACC002",
                                  "amount":
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("MALFORMED_JSON"))
                .andExpect(jsonPath("$.message").value("Request body is malformed or unreadable"))
                .andReturn();

        assertThat(result.getResponse().getHeader(CorrelationIdFilter.HEADER_NAME)).isEqualTo("corr-transfer-001");
        assertErrorContract(result, "/api/v1/transfers");
    }

    @Test
    void shouldReturnStructuredErrorForUnsupportedMethod() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/transfers"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.errorCode").value("METHOD_NOT_ALLOWED"))
                .andReturn();

        assertErrorContract(result, "/api/v1/transfers");
    }

    @Test
    void shouldReturnStructuredErrorForUnknownRoute() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/unknown-route"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("ROUTE_NOT_FOUND"))
                .andReturn();

        assertErrorContract(result, "/api/v1/unknown-route");
    }

    @Test
    void shouldReplayConcurrentIdenticalTransferRequests() throws Exception {
        saveCustomerTransaction("CUST-001", "Alice", "ACC001", "TXN-001", TransactionType.CREDIT, "idem-ct-1", "5000.00", "5000.00");
        saveCustomerTransaction("CUST-002", "Bob", "ACC002", "TXN-002", TransactionType.CREDIT, "idem-ct-2", "2000.00", "2000.00");

        List<MvcResult> results = runConcurrentTransfers(
                """
                        {
                          "sourceAccountNumber": "ACC001",
                          "destinationAccountNumber": "ACC002",
                          "amount": 1000.00,
                          "transferReference": "TRF-CONCURRENT-1"
                        }
                        """,
                """
                        {
                          "sourceAccountNumber": "ACC001",
                          "destinationAccountNumber": "ACC002",
                          "amount": 1000.00,
                          "transferReference": "TRF-CONCURRENT-1"
                        }
                        """,
                "idem-tr-concurrent"
        );

        assertThat(results.stream().map(result -> result.getResponse().getStatus()))
                .containsExactlyInAnyOrder(201, 200);
        assertThat(results.stream().map(this::responseBody))
                .anyMatch(body -> body.contains("Funds transfer already processed for this idempotency key"));
        assertThat(transferTransactionRepository.count()).isEqualTo(1L);
        assertThat(customerTransactionDetailRepository.findByAccountNumber("ACC001").orElseThrow().getCurrentBalance())
                .isEqualByComparingTo("4000.00");
        assertThat(customerTransactionDetailRepository.findByAccountNumber("ACC002").orElseThrow().getCurrentBalance())
                .isEqualByComparingTo("3000.00");
    }

    @Test
    void shouldRejectConcurrentTransferRequestsWithSameKeyDifferentPayloads() throws Exception {
        saveCustomerTransaction("CUST-001", "Alice", "ACC001", "TXN-001", TransactionType.CREDIT, "idem-ct-1", "5000.00", "5000.00");
        saveCustomerTransaction("CUST-002", "Bob", "ACC002", "TXN-002", TransactionType.CREDIT, "idem-ct-2", "2000.00", "2000.00");

        List<MvcResult> results = runConcurrentTransfers(
                """
                        {
                          "sourceAccountNumber": "ACC001",
                          "destinationAccountNumber": "ACC002",
                          "amount": 1000.00,
                          "transferReference": "TRF-CONCURRENT-2"
                        }
                        """,
                """
                        {
                          "sourceAccountNumber": "ACC001",
                          "destinationAccountNumber": "ACC002",
                          "amount": 500.00,
                          "transferReference": "TRF-CONCURRENT-3"
                        }
                        """,
                "idem-tr-concurrent-conflict"
        );

        assertThat(results.stream().map(result -> result.getResponse().getStatus()))
                .containsExactlyInAnyOrder(201, 409);
        assertThat(results.stream().map(this::responseBody))
                .anyMatch(body -> body.contains("\"errorCode\":\"IDEMPOTENCY_KEY_CONFLICT\""));
        assertThat(transferTransactionRepository.count()).isEqualTo(1L);
        TransferTransaction savedTransfer = transferTransactionRepository.findAll().getFirst();
        assertThat(savedTransfer.getAmount()).isIn(new BigDecimal("1000.00"), new BigDecimal("500.00"));
        if (savedTransfer.getAmount().compareTo(new BigDecimal("1000.00")) == 0) {
            assertThat(customerTransactionDetailRepository.findByAccountNumber("ACC001").orElseThrow().getCurrentBalance())
                    .isEqualByComparingTo("4000.00");
            assertThat(customerTransactionDetailRepository.findByAccountNumber("ACC002").orElseThrow().getCurrentBalance())
                    .isEqualByComparingTo("3000.00");
        } else {
            assertThat(customerTransactionDetailRepository.findByAccountNumber("ACC001").orElseThrow().getCurrentBalance())
                    .isEqualByComparingTo("4500.00");
            assertThat(customerTransactionDetailRepository.findByAccountNumber("ACC002").orElseThrow().getCurrentBalance())
                    .isEqualByComparingTo("2500.00");
        }
    }

    private List<MvcResult> runConcurrentTransfers(String firstRequestBody, String secondRequestBody, String idempotencyKey)
            throws Exception {
        try (ExecutorService executorService = Executors.newFixedThreadPool(2)) {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);

            Future<MvcResult> first = executorService.submit(buildConcurrentTransfer(firstRequestBody, idempotencyKey, ready, start));
            Future<MvcResult> second = executorService.submit(buildConcurrentTransfer(secondRequestBody, idempotencyKey, ready, start));

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            return List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        }
    }

    private Callable<MvcResult> buildConcurrentTransfer(
            String requestBody,
            String idempotencyKey,
            CountDownLatch ready,
            CountDownLatch start
    ) {
        return () -> {
            ready.countDown();
            assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
            return mockMvc.perform(post("/api/v1/transfers")
                            .header("Idempotency-Key", idempotencyKey)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andReturn();
        };
    }

    private String responseBody(MvcResult result) {
        try {
            return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new AssertionError("Failed to read response body", ex);
        }
    }

    private void assertErrorContract(MvcResult result, String expectedPath) {
        String responseBody = responseBody(result);
        String correlationId = result.getResponse().getHeader(CorrelationIdFilter.HEADER_NAME);

        assertThat(correlationId).isNotBlank();
        assertThat(responseBody).contains("\"success\":false");
        assertThat(responseBody).contains("\"path\":\"" + expectedPath + "\"");
        assertThat(responseBody).contains("\"timestamp\":");
        assertThat(responseBody).contains("\"correlationId\":\"" + correlationId + "\"");
        assertThat(responseBody).doesNotContain("java.lang");
        assertThat(responseBody).doesNotContain("org.springframework");
        assertThat(responseBody).doesNotContain("SELECT ");
        assertThat(responseBody).doesNotContain("password");
    }

    private void saveCustomerTransaction(
            String customerId,
            String customerName,
            String accountNumber,
            String transactionReference,
            TransactionType transactionType,
            String idempotencyKey,
            String transactionAmount,
            String currentBalance
    ) {
        CustomerTransactionDetail transactionDetail = new CustomerTransactionDetail();
        transactionDetail.setCustomerId(customerId);
        transactionDetail.setCustomerName(customerName);
        transactionDetail.setAccountNumber(accountNumber);
        transactionDetail.setTransactionReference(transactionReference);
        transactionDetail.setIdempotencyKey(idempotencyKey);
        transactionDetail.setTransactionType(transactionType);
        transactionDetail.setTransactionAmount(new BigDecimal(transactionAmount));
        transactionDetail.setCurrentBalance(new BigDecimal(currentBalance));
        customerTransactionDetailRepository.save(transactionDetail);
    }
}
