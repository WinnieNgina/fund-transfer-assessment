package com.assessment.fundtransfer.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;

public record TransferRequest(
        @Schema(example = "ACC001", description = "Source customer account number")
        @NotBlank(message = "Source account number is required")
        @Pattern(
                regexp = "^\\s*[A-Za-z0-9]{3,20}\\s*$",
                message = "Source account number must be 3-20 alphanumeric characters"
        )
        String sourceAccountNumber,

        @Schema(example = "ACC002", description = "Destination customer account number")
        @NotBlank(message = "Destination account number is required")
        @Pattern(
                regexp = "^\\s*[A-Za-z0-9]{3,20}\\s*$",
                message = "Destination account number must be 3-20 alphanumeric characters"
        )
        String destinationAccountNumber,

        @Schema(example = "1000.00", description = "Transfer amount stored as NUMERIC(19,2)")
        @NotNull(message = "Amount is required")
        @DecimalMin(value = "0.01", message = "Amount must be greater than zero")
        @Digits(integer = 17, fraction = 2, message = "Amount must have at most 2 decimal places")
        BigDecimal amount,

        @Schema(
                example = "INV-20260803-0001",
                description = "Client-supplied business reference for this transfer. The API trims and uppercases it."
        )
        @NotBlank(message = "Transfer reference is required")
        @Pattern(
                regexp = "^\\s*[A-Za-z0-9_-]{3,64}\\s*$",
                message = "Transfer reference must be 3-64 characters using letters, numbers, hyphens, or underscores"
        )
        String transferReference
) {
}
