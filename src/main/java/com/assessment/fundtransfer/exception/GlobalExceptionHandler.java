package com.assessment.fundtransfer.exception;

import com.assessment.fundtransfer.dto.response.ApiErrorResponse;
import com.assessment.fundtransfer.dto.response.ApiValidationError;
import com.assessment.fundtransfer.web.CorrelationIdFilter;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PessimisticLockException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(
            MethodArgumentNotValidException ex,
            HttpServletRequest request
    ) {
        List<ApiValidationError> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(this::toValidationError)
                .toList();
        log.warn("Validation failed for path {}: {}", request.getRequestURI(), errors);
        return validationFailure(HttpStatus.BAD_REQUEST, "Validation failed", errors, request, ApiErrorCode.VALIDATION_ERROR);
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<ApiErrorResponse> handleBindException(
            BindException ex,
            HttpServletRequest request
    ) {
        List<ApiValidationError> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(this::toValidationError)
                .toList();
        log.warn("Binding failed for path {}: {}", request.getRequestURI(), errors);
        return validationFailure(HttpStatus.BAD_REQUEST, "Validation failed", errors, request, ApiErrorCode.VALIDATION_ERROR);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleConstraintViolation(
            ConstraintViolationException ex,
            HttpServletRequest request
    ) {
        List<ApiValidationError> errors = ex.getConstraintViolations().stream()
                .map(this::toValidationError)
                .toList();
        log.warn("Constraint violation for path {}: {}", request.getRequestURI(), errors);
        return validationFailure(HttpStatus.BAD_REQUEST, "Validation failed", errors, request, ApiErrorCode.VALIDATION_ERROR);
    }

    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidRequest(
            InvalidRequestException ex,
            HttpServletRequest request
    ) {
        log.warn("Invalid request for path {}: {}", request.getRequestURI(), ex.getMessage());
        return failure(HttpStatus.BAD_REQUEST, ex.getMessage(), request, ApiErrorCode.INVALID_REQUEST);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleMessageNotReadable(
            HttpMessageNotReadableException ex,
            HttpServletRequest request
    ) {
        String message = ex.getCause() instanceof InvalidFormatException
                ? "Request body contains a value with the wrong format"
                : "Request body is malformed or unreadable";
        log.warn("Malformed request body for path {}: {}", request.getRequestURI(), ex.getMessage());
        return failure(HttpStatus.BAD_REQUEST, message, request, ApiErrorCode.MALFORMED_JSON);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiErrorResponse> handleMissingRequestHeader(
            MissingRequestHeaderException ex,
            HttpServletRequest request
    ) {
        String message = "Required request header '" + ex.getHeaderName() + "' is missing";
        log.warn("Missing request header for path {}: {}", request.getRequestURI(), ex.getHeaderName());
        return failure(HttpStatus.BAD_REQUEST, message, request, ApiErrorCode.MISSING_REQUEST_HEADER);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiErrorResponse> handleMissingRequestParameter(
            MissingServletRequestParameterException ex,
            HttpServletRequest request
    ) {
        String message = "Required request parameter '" + ex.getParameterName() + "' is missing";
        log.warn("Missing request parameter for path {}: {}", request.getRequestURI(), ex.getParameterName());
        return failure(HttpStatus.BAD_REQUEST, message, request, ApiErrorCode.MISSING_REQUEST_PARAMETER);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex,
            HttpServletRequest request
    ) {
        String message = "Request parameter '" + ex.getName() + "' has an invalid value";
        log.warn("Type mismatch for path {}: {}", request.getRequestURI(), ex.getMessage());
        return failure(HttpStatus.BAD_REQUEST, message, request, ApiErrorCode.TYPE_MISMATCH);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException ex,
            HttpServletRequest request
    ) {
        String message = "HTTP method '" + ex.getMethod() + "' is not supported for this endpoint";
        log.warn("Method not supported for path {}: {}", request.getRequestURI(), ex.getMethod());
        return failure(HttpStatus.METHOD_NOT_ALLOWED, message, request, ApiErrorCode.METHOD_NOT_ALLOWED);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleRouteNotFound(
            NoResourceFoundException ex,
            HttpServletRequest request
    ) {
        log.warn("No route found for path {}: {}", request.getRequestURI(), ex.getResourcePath());
        return failure(HttpStatus.NOT_FOUND, "The requested endpoint does not exist", request, ApiErrorCode.ROUTE_NOT_FOUND);
    }

    @ExceptionHandler(MissingIdempotencyKeyException.class)
    public ResponseEntity<ApiErrorResponse> handleMissingIdempotencyKey(
            MissingIdempotencyKeyException ex,
            HttpServletRequest request
    ) {
        log.warn("Missing idempotency key for path {}: {}", request.getRequestURI(), ex.getMessage());
        return failure(HttpStatus.BAD_REQUEST, ex.getMessage(), request, ApiErrorCode.MISSING_IDEMPOTENCY_KEY);
    }

    @ExceptionHandler(InsufficientFundsException.class)
    public ResponseEntity<ApiErrorResponse> handleInsufficientFunds(
            InsufficientFundsException ex,
            HttpServletRequest request
    ) {
        log.warn("Insufficient funds for path {}: {}", request.getRequestURI(), ex.getMessage());
        return failure(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "Insufficient funds in source account",
                request,
                ApiErrorCode.INSUFFICIENT_FUNDS
        );
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ApiErrorResponse> handleIdempotencyConflict(
            IdempotencyConflictException ex,
            HttpServletRequest request
    ) {
        log.warn("Idempotency conflict for path {}: {}", request.getRequestURI(), ex.getMessage());
        return failure(
                HttpStatus.CONFLICT,
                "Idempotency key has already been used with a different request payload",
                request,
                ApiErrorCode.IDEMPOTENCY_KEY_CONFLICT
        );
    }

    @ExceptionHandler(DuplicateTransferReferenceException.class)
    public ResponseEntity<ApiErrorResponse> handleDuplicateTransferReference(
            DuplicateTransferReferenceException ex,
            HttpServletRequest request
    ) {
        log.warn("Duplicate transfer reference for path {}: {}", request.getRequestURI(), ex.getMessage());
        return failure(HttpStatus.CONFLICT, "Transfer reference already exists", request, ApiErrorCode.DUPLICATE_TRANSFER_REFERENCE);
    }

    @ExceptionHandler(DuplicateTransactionReferenceException.class)
    public ResponseEntity<ApiErrorResponse> handleDuplicateTransactionReference(
            DuplicateTransactionReferenceException ex,
            HttpServletRequest request
    ) {
        log.warn("Duplicate transaction reference for path {}: {}", request.getRequestURI(), ex.getMessage());
        return failure(
                HttpStatus.CONFLICT,
                "Transaction reference already exists",
                request,
                ApiErrorCode.DUPLICATE_TRANSACTION_REFERENCE
        );
    }

    @ExceptionHandler(DuplicateAccountException.class)
    public ResponseEntity<ApiErrorResponse> handleDuplicateAccount(
            DuplicateAccountException ex,
            HttpServletRequest request
    ) {
        log.warn("Duplicate account rejected for path {}: {}", request.getRequestURI(), ex.getMessage());
        return failure(HttpStatus.CONFLICT, "Account number already exists", request, ApiErrorCode.DUPLICATE_ACCOUNT);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleDataIntegrityViolation(
            DataIntegrityViolationException ex,
            HttpServletRequest request
    ) {
        String message = ex.getMostSpecificCause() == null ? ex.getMessage() : ex.getMostSpecificCause().getMessage();
        log.warn("Data integrity violation for path {}: {}", request.getRequestURI(), message);
        return failure(HttpStatus.CONFLICT, "Request conflicts with existing data", request, ApiErrorCode.DATA_CONFLICT);
    }

    @ExceptionHandler(AccountNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleAccountNotFound(
            AccountNotFoundException ex,
            HttpServletRequest request
    ) {
        log.warn("Account lookup failed for path {}: {}", request.getRequestURI(), ex.getMessage());
        return failure(HttpStatus.NOT_FOUND, ex.getMessage(), request, ApiErrorCode.ACCOUNT_NOT_FOUND);
    }

    @ExceptionHandler({ObjectOptimisticLockingFailureException.class, OptimisticLockException.class})
    public ResponseEntity<ApiErrorResponse> handleOptimisticLocking(
            RuntimeException ex,
            HttpServletRequest request
    ) {
        log.warn("Concurrent modification conflict for path {}: {}", request.getRequestURI(), ex.getMessage());
        return failure(
                HttpStatus.CONFLICT,
                "Transfer could not be completed because the account was modified concurrently",
                request,
                ApiErrorCode.LOCK_CONFLICT
        );
    }

    @ExceptionHandler({
            PessimisticLockingFailureException.class,
            CannotAcquireLockException.class,
            LockTimeoutException.class,
            PessimisticLockException.class
    })
    public ResponseEntity<ApiErrorResponse> handleLockConflict(
            RuntimeException ex,
            HttpServletRequest request
    ) {
        log.warn("Lock conflict for path {}: {}", request.getRequestURI(), ex.getMessage());
        return failure(
                HttpStatus.CONFLICT,
                "Transfer could not be completed because the account is busy",
                request,
                ApiErrorCode.LOCK_CONFLICT
        );
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleIllegalArgument(
            IllegalArgumentException ex,
            HttpServletRequest request
    ) {
        log.warn("Unhandled illegal argument for path {}: {}", request.getRequestURI(), ex.getMessage());
        return failure(HttpStatus.BAD_REQUEST, "Request could not be processed", request, ApiErrorCode.INVALID_REQUEST);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error(
                "Unexpected error occurred for path {} with correlationId={}",
                request.getRequestURI(),
                correlationId(request),
                ex
        );
        return failure(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred",
                request,
                ApiErrorCode.INTERNAL_SERVER_ERROR
        );
    }

    private ApiValidationError toValidationError(FieldError fieldError) {
        return new ApiValidationError(fieldError.getField(), fieldError.getDefaultMessage(), fieldError.getCode());
    }

    private ApiValidationError toValidationError(ConstraintViolation<?> violation) {
        String field = null;
        for (var node : violation.getPropertyPath()) {
            field = node.getName();
        }
        return new ApiValidationError(field, violation.getMessage(), "CONSTRAINT_VIOLATION");
    }

    private ResponseEntity<ApiErrorResponse> validationFailure(
            HttpStatus status,
            String message,
            List<ApiValidationError> errors,
            HttpServletRequest request,
            ApiErrorCode errorCode
    ) {
        return ResponseEntity.status(status)
                .body(ApiErrorResponse.validationFailure(
                        message,
                        errors,
                        request.getRequestURI(),
                        errorCode.name(),
                        correlationId(request)
                ));
    }

    private ResponseEntity<ApiErrorResponse> failure(
            HttpStatus status,
            String message,
            HttpServletRequest request,
            ApiErrorCode errorCode
    ) {
        return ResponseEntity.status(status)
                .body(ApiErrorResponse.failure(
                        message,
                        request.getRequestURI(),
                        errorCode.name(),
                        correlationId(request)
                ));
    }

    private String correlationId(HttpServletRequest request) {
        String correlationId = CorrelationIdFilter.getCorrelationId(request);
        return correlationId == null ? "unavailable" : correlationId;
    }
}
