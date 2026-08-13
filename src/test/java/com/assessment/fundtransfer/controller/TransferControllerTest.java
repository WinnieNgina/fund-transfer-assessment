package com.assessment.fundtransfer.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.assessment.fundtransfer.dto.request.TransferRequest;
import com.assessment.fundtransfer.dto.response.TransferResponse;
import com.assessment.fundtransfer.entity.TransactionStatus;
import com.assessment.fundtransfer.exception.DuplicateTransferReferenceException;
import com.assessment.fundtransfer.exception.IdempotencyConflictException;
import com.assessment.fundtransfer.exception.MissingIdempotencyKeyException;
import com.assessment.fundtransfer.service.IdempotentResult;
import com.assessment.fundtransfer.service.TransferService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(TransferController.class)
class TransferControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TransferService transferService;

    @Test
    void postTransfersReturns201() throws Exception {
        TransferResponse response = new TransferResponse(
                "INV-001",
                "ACC001",
                "ACC002",
                new BigDecimal("1000.00"),
                new BigDecimal("4000.00"),
                new BigDecimal("3000.00"),
                TransactionStatus.SUCCESS,
                LocalDateTime.now()
        );
        when(transferService.transfer(any(TransferRequest.class), eq("idem-1")))
                .thenReturn(new IdempotentResult<>(response, false));

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "idem-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceAccountNumber": "ACC001",
                                  "destinationAccountNumber": "ACC002",
                                  "amount": 1000.00,
                                  "transferReference": "INV-001"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Transfer completed successfully"))
                .andExpect(jsonPath("$.data.transferReference").value("INV-001"))
                .andExpect(jsonPath("$.data.sourceBalanceAfterTransfer").value(4000.00))
                .andExpect(jsonPath("$.data.destinationBalanceAfterTransfer").value(3000.00))
                .andExpect(jsonPath("$.path").value("/api/v1/transfers"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void postTransfersReturns200ForIdempotentReplay() throws Exception {
        TransferResponse response = new TransferResponse(
                "INV-001",
                "ACC001",
                "ACC002",
                new BigDecimal("1000.00"),
                new BigDecimal("4000.00"),
                new BigDecimal("3000.00"),
                TransactionStatus.SUCCESS,
                LocalDateTime.now()
        );
        when(transferService.transfer(any(TransferRequest.class), eq("idem-1")))
                .thenReturn(new IdempotentResult<>(response, true));

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "idem-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceAccountNumber": "ACC001",
                                  "destinationAccountNumber": "ACC002",
                                  "amount": 1000.00,
                                  "transferReference": "INV-001"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Funds transfer already processed for this idempotency key"));
    }

    @Test
    void postTransfersRejectsMissingIdempotencyKey() throws Exception {
        when(transferService.transfer(any(TransferRequest.class), eq((String) null)))
                .thenThrow(new MissingIdempotencyKeyException("Idempotency-Key header is required"));

        mockMvc.perform(post("/api/v1/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceAccountNumber": "ACC001",
                                  "destinationAccountNumber": "ACC002",
                                  "amount": 1000.00,
                                  "transferReference": "INV-001"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Idempotency-Key header is required"))
                .andExpect(jsonPath("$.errorCode").value("MISSING_IDEMPOTENCY_KEY"));
    }

    @Test
    void postTransfersRejectsZeroOrNegativeAmount() throws Exception {
        mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "idem-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceAccountNumber": "ACC001",
                                  "destinationAccountNumber": "ACC002",
                                  "amount": 0,
                                  "transferReference": "INV-002"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[?(@.field=='amount')].message",
                        Matchers.hasItem("Amount must be greater than zero")));
    }

    @Test
    void postTransfersRejectsInvalidAccountFormat() throws Exception {
        mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "idem-3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceAccountNumber": "ACC-001",
                                  "destinationAccountNumber": "ACC002",
                                  "amount": 10.00,
                                  "transferReference": "INV-003"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[?(@.field=='sourceAccountNumber')].message",
                        Matchers.hasItem("Source account number must be 3-20 alphanumeric characters")));
    }

    @Test
    void postTransfersReturns409ForLockConflict() throws Exception {
        when(transferService.transfer(any(TransferRequest.class), eq("idem-4")))
                .thenThrow(new org.springframework.dao.CannotAcquireLockException("busy"));

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "idem-4")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceAccountNumber": "ACC001",
                                  "destinationAccountNumber": "ACC002",
                                  "amount": 1000.00,
                                  "transferReference": "INV-004"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Transfer could not be completed because the account is busy"))
                .andExpect(jsonPath("$.errorCode").value("LOCK_CONFLICT"))
                .andExpect(jsonPath("$.path").value("/api/v1/transfers"));
    }

    @Test
    void postTransfersReturns409ForIdempotencyConflict() throws Exception {
        when(transferService.transfer(any(TransferRequest.class), eq("idem-5")))
                .thenThrow(new IdempotencyConflictException(
                        "Idempotency key was already used for a different transfer request"
                ));

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "idem-5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceAccountNumber": "ACC001",
                                  "destinationAccountNumber": "ACC002",
                                  "amount": 1000.00,
                                  "transferReference": "INV-005"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_KEY_CONFLICT"));
    }

    @Test
    void postTransfersReturns409ForDuplicateTransferReference() throws Exception {
        when(transferService.transfer(any(TransferRequest.class), eq("idem-6")))
                .thenThrow(new DuplicateTransferReferenceException("Transfer reference already exists"));

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "idem-6")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceAccountNumber": "ACC001",
                                  "destinationAccountNumber": "ACC002",
                                  "amount": 1000.00,
                                  "transferReference": "INV-006"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Transfer reference already exists"))
                .andExpect(jsonPath("$.errorCode").value("DUPLICATE_TRANSFER_REFERENCE"));
    }
}
