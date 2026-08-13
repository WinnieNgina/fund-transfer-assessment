package com.assessment.fundtransfer.dto.response;

import com.assessment.fundtransfer.entity.TransactionStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record TransferResponse(
        String transferReference,
        String sourceAccountNumber,
        String destinationAccountNumber,
        BigDecimal amount,
        BigDecimal sourceBalanceAfterTransfer,
        BigDecimal destinationBalanceAfterTransfer,
        TransactionStatus status,
        LocalDateTime createdAt
) {
}
