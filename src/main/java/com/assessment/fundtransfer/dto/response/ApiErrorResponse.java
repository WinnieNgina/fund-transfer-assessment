package com.assessment.fundtransfer.dto.response;

import java.time.OffsetDateTime;
import java.util.List;

public record ApiErrorResponse(
        boolean success,
        String message,
        List<ApiValidationError> errors,
        OffsetDateTime timestamp,
        String path,
        String errorCode,
        String correlationId
) {

    public static ApiErrorResponse failure(
            String message,
            String path,
            String errorCode,
            String correlationId
    ) {
        return new ApiErrorResponse(false, message, null, OffsetDateTime.now(), path, errorCode, correlationId);
    }

    public static ApiErrorResponse validationFailure(
            String message,
            List<ApiValidationError> errors,
            String path,
            String errorCode,
            String correlationId
    ) {
        return new ApiErrorResponse(false, message, errors, OffsetDateTime.now(), path, errorCode, correlationId);
    }
}
