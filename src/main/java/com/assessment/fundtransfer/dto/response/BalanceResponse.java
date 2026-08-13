package com.assessment.fundtransfer.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record BalanceResponse(
        String customerId,
        String customerName,
        String accountNumber,
        BigDecimal currentBalance,
        LocalDateTime asOf
) {
}
