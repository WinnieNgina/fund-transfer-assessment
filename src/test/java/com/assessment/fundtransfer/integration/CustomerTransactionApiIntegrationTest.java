package com.assessment.fundtransfer.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.assessment.fundtransfer.TestcontainersConfiguration;
import com.assessment.fundtransfer.entity.CustomerTransactionDetail;
import com.assessment.fundtransfer.entity.TransactionType;
import com.assessment.fundtransfer.repository.CustomerTransactionDetailRepository;
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
class CustomerTransactionApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CustomerTransactionDetailRepository customerTransactionDetailRepository;

    @BeforeEach
    void setUp() {
        customerTransactionDetailRepository.deleteAll();
    }

    @Test
    void shouldSaveAndPersistCustomerTransactionDetails() throws Exception {
        mockMvc.perform(post("/api/v1/customer-accounts")
                        .header("Idempotency-Key", "idem-ct-1")
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
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.customerId").value("CUST-001"))
                .andExpect(jsonPath("$.data.accountNumber").value("ACC001"))
                .andExpect(jsonPath("$.data.currentBalance").value(5000.00))
                .andExpect(jsonPath("$.data.transactionType").doesNotExist())
                .andExpect(jsonPath("$.path").value("/api/v1/customer-accounts"));

        CustomerTransactionDetail saved = customerTransactionDetailRepository.findByAccountNumber("ACC001").orElseThrow();
        assertThat(saved.getIdempotencyKey()).isEqualTo("idem-ct-1");
        assertThat(saved.getTransactionType()).isEqualTo(TransactionType.CREDIT);
        assertThat(saved.getCurrentBalance()).isEqualByComparingTo("5000.00");
    }

    @Test
    void shouldReplayCustomerTransactionDetailsByIdempotencyKey() throws Exception {
        mockMvc.perform(post("/api/v1/customer-accounts")
                        .header("Idempotency-Key", "idem-ct-2")
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
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/customer-accounts")
                        .header("Idempotency-Key", "idem-ct-2")
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

        assertThat(customerTransactionDetailRepository.count()).isEqualTo(1L);
    }

    @Test
    void shouldRejectReusedIdempotencyKeyWithDifferentCustomerTransactionPayload() throws Exception {
        mockMvc.perform(post("/api/v1/customer-accounts")
                        .header("Idempotency-Key", "idem-ct-conflict")
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
                .andExpect(status().isCreated());

        MvcResult result = mockMvc.perform(post("/api/v1/customer-accounts")
                        .header("Idempotency-Key", "idem-ct-conflict")
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
                .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_KEY_CONFLICT"))
                .andReturn();

        assertErrorContract(result, "/api/v1/customer-accounts");

        assertThat(customerTransactionDetailRepository.count()).isEqualTo(1L);
    }

    @Test
    void shouldRejectMissingIdempotencyKey() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/customer-accounts")
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
                .andExpect(jsonPath("$.errorCode").value("MISSING_IDEMPOTENCY_KEY"))
                .andReturn();

        assertErrorContract(result, "/api/v1/customer-accounts");
    }

    @Test
    void shouldRejectOverscaleTransactionAmount() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/customer-accounts")
                        .header("Idempotency-Key", "idem-ct-scale")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "customerId": "CUST-001",
                                  "customerName": "Alice",
                                  "accountNumber": "ACC001",
                                  "transactionReference": "TXN-SCALE",
                                  "transactionAmount": 10.999
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("transactionAmount"))
                .andExpect(jsonPath("$.errors[0].message")
                        .value("Transaction amount must have at most 2 decimal places"))
                .andReturn();

        assertErrorContract(result, "/api/v1/customer-accounts");
    }

    @Test
    void shouldPreventDuplicateAccountNumbers() throws Exception {
        saveCustomerTransaction("CUST-001", "Alice", "ACC001", "TXN-001", TransactionType.CREDIT, "idem-existing-1", "5000.00", "5000.00");

        MvcResult result = mockMvc.perform(post("/api/v1/customer-accounts")
                        .header("Idempotency-Key", "idem-ct-3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "customerId": "CUST-002",
                                  "customerName": "Bob",
                                  "accountNumber": "ACC001",
                                  "transactionReference": "TXN-002",
                                  "transactionAmount": 1000.00
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DUPLICATE_ACCOUNT"))
                .andReturn();

        assertErrorContract(result, "/api/v1/customer-accounts");
    }

    @Test
    void shouldPreventDuplicateTransactionReferences() throws Exception {
        saveCustomerTransaction("CUST-001", "Alice", "ACC001", "TXN-001", TransactionType.CREDIT, "idem-existing-2", "5000.00", "5000.00");

        MvcResult result = mockMvc.perform(post("/api/v1/customer-accounts")
                        .header("Idempotency-Key", "idem-ct-4")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "customerId": "CUST-002",
                                  "customerName": "Bob",
                                  "accountNumber": "ACC002",
                                  "transactionReference": "TXN-001",
                                  "transactionAmount": 1000.00
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DUPLICATE_TRANSACTION_REFERENCE"))
                .andReturn();

        assertErrorContract(result, "/api/v1/customer-accounts");
    }

    @Test
    void shouldReturnCurrentBalance() throws Exception {
        CustomerTransactionDetail transactionDetail = new CustomerTransactionDetail();
        transactionDetail.setCustomerId("CUST-001");
        transactionDetail.setCustomerName("Alice");
        transactionDetail.setAccountNumber("ACC001");
        transactionDetail.setTransactionReference("TXN-001");
        transactionDetail.setIdempotencyKey("idem-ct-balance");
        transactionDetail.setTransactionType(TransactionType.CREDIT);
        transactionDetail.setTransactionAmount(new BigDecimal("5000.00"));
        transactionDetail.setCurrentBalance(new BigDecimal("5000.00"));
        customerTransactionDetailRepository.save(transactionDetail);

        mockMvc.perform(get("/api/v1/customer-accounts/ACC001/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.customerId").value("CUST-001"))
                .andExpect(jsonPath("$.data.currentBalance").value(5000.00));
    }

    @Test
    void shouldReplayConcurrentIdenticalCustomerAccountRequests() throws Exception {
        List<MvcResult> results = runConcurrently(
                """
                        {
                          "customerId": "CUST-001",
                          "customerName": "Alice",
                          "accountNumber": "ACC001",
                          "transactionReference": "TXN-CONCURRENT-1",
                          "transactionAmount": 5000.00
                        }
                        """,
                """
                        {
                          "customerId": "CUST-001",
                          "customerName": "Alice",
                          "accountNumber": "ACC001",
                          "transactionReference": "TXN-CONCURRENT-1",
                          "transactionAmount": 5000.00
                        }
                        """,
                "idem-ct-concurrent"
        );

        assertThat(results.stream().map(result -> result.getResponse().getStatus()))
                .containsExactlyInAnyOrder(201, 200);
        assertThat(results.stream().map(this::responseBody))
                .anyMatch(body -> body.contains("Customer account funding already processed for this idempotency key"));
        assertThat(customerTransactionDetailRepository.count()).isEqualTo(1L);
    }

    @Test
    void shouldRejectConcurrentCustomerAccountRequestsWithSameKeyDifferentPayloads() throws Exception {
        List<MvcResult> results = runConcurrently(
                """
                        {
                          "customerId": "CUST-001",
                          "customerName": "Alice",
                          "accountNumber": "ACC001",
                          "transactionReference": "TXN-CONCURRENT-2",
                          "transactionAmount": 5000.00
                        }
                        """,
                """
                        {
                          "customerId": "CUST-002",
                          "customerName": "Bob",
                          "accountNumber": "ACC002",
                          "transactionReference": "TXN-CONCURRENT-3",
                          "transactionAmount": 2500.00
                        }
                        """,
                "idem-ct-concurrent-conflict"
        );

        assertThat(results.stream().map(result -> result.getResponse().getStatus()))
                .containsExactlyInAnyOrder(201, 409);
        assertThat(results.stream().map(this::responseBody))
                .anyMatch(body -> body.contains("\"errorCode\":\"IDEMPOTENCY_KEY_CONFLICT\""));
        assertThat(customerTransactionDetailRepository.count()).isEqualTo(1L);
    }

    private List<MvcResult> runConcurrently(String firstRequestBody, String secondRequestBody, String idempotencyKey)
            throws Exception {
        try (ExecutorService executorService = Executors.newFixedThreadPool(2)) {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);

            Future<MvcResult> first = executorService.submit(buildConcurrentPost(firstRequestBody, idempotencyKey, ready, start));
            Future<MvcResult> second = executorService.submit(buildConcurrentPost(secondRequestBody, idempotencyKey, ready, start));

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            return List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        }
    }

    private Callable<MvcResult> buildConcurrentPost(
            String requestBody,
            String idempotencyKey,
            CountDownLatch ready,
            CountDownLatch start
    ) {
        return () -> {
            ready.countDown();
            assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
            return mockMvc.perform(post("/api/v1/customer-accounts")
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
