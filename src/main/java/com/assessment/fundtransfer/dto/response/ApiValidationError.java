package com.assessment.fundtransfer.dto.response;

public record ApiValidationError(
        String field,
        String message,
        String code
) {
}
