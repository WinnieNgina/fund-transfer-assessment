package com.assessment.fundtransfer.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record CustomerTransactionRequest(
        @Schema(example = "CUST-001", description = "Customer identifier")
        @NotBlank(message = "Customer identifier is required")
        @Size(max = 50, message = "Customer identifier must not exceed 50 characters")
        String customerId,

        @Schema(example = "Alice", description = "Customer full name")
        @NotBlank(message = "Customer name is required")
        @Size(max = 100, message = "Customer name must not exceed 100 characters")
        String customerName,

        @Schema(example = "ACC001", description = "Unique customer account number")
        @NotBlank(message = "Account number is required")
        @Size(max = 20, message = "Account number must not exceed 20 characters")
        @Pattern(
                regexp = "^\\s*[A-Za-z0-9]{3,20}\\s*$",
                message = "Account number must be 3-20 alphanumeric characters"
        )
        String accountNumber,

        @Schema(example = "TXN-001", description = "Unique transaction reference for the saved customer transaction detail")
        @NotBlank(message = "Transaction reference is required")
        @Pattern(
                regexp = "^\\s*[A-Za-z0-9_-]{3,64}\\s*$",
                message = "Transaction reference must be 3-64 characters using letters, numbers, hyphens, or underscores"
        )
        String transactionReference,

        @Schema(example = "5000.00", description = "Funding amount for the new customer account stored as NUMERIC(19,2)")
        @NotNull(message = "Transaction amount is required")
        @DecimalMin(value = "0.00", message = "Transaction amount cannot be negative")
        @Digits(integer = 17, fraction = 2, message = "Transaction amount must have at most 2 decimal places")
        BigDecimal transactionAmount
) {
}
