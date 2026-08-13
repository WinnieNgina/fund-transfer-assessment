package com.assessment.fundtransfer.dto.response;

import java.time.OffsetDateTime;

public record ApiResponse<T>(
        boolean success,
        String message,
        T data,
        Object errors,
        OffsetDateTime timestamp,
        String path,
        String errorCode
) {

    public static <T> ApiResponse<T> success(String message, T data, String path) {
        return new ApiResponse<>(true, message, data, null, OffsetDateTime.now(), path, null);
    }

    public static <T> ApiResponse<T> failure(String message, String path, String errorCode) {
        return new ApiResponse<>(false, message, null, null, OffsetDateTime.now(), path, errorCode);
    }

    public static <T> ApiResponse<T> validationFailure(
            String message,
            Object errors,
            String path,
            String errorCode
    ) {
        return new ApiResponse<>(false, message, null, errors, OffsetDateTime.now(), path, errorCode);
    }
}
