package com.assessment.fundtransfer.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record CustomerTransactionResponse(
        Long id,
        String customerId,
        String customerName,
        String accountNumber,
        String transactionReference,
        BigDecimal transactionAmount,
        BigDecimal currentBalance,
        LocalDateTime createdAt
) {
}
