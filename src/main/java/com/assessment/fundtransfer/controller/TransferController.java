package com.assessment.fundtransfer.controller;

import com.assessment.fundtransfer.config.ApiPaths;
import com.assessment.fundtransfer.dto.request.TransferRequest;
import com.assessment.fundtransfer.dto.response.ApiResponse;
import com.assessment.fundtransfer.dto.response.TransferResponse;
import com.assessment.fundtransfer.service.IdempotentResult;
import com.assessment.fundtransfer.service.TransferService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(ApiPaths.TRANSFERS)
@Tag(
        name = "Fund Transfers",
        description = "Operations for moving funds between existing customer accounts."
)
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    @PostMapping
    @Operation(
            summary = "Transfer funds between accounts",
            description = "Transfers funds from one persisted customer account to another. Reusing the same Idempotency-Key with the same normalized payload replays the original response."
    )
    public ResponseEntity<ApiResponse<TransferResponse>> transfer(
            @Parameter(description = "Unique request key used to safely retry the same transfer")
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody TransferRequest request,
            HttpServletRequest httpRequest
    ) {
        IdempotentResult<TransferResponse> result = transferService.transfer(request, idempotencyKey);
        HttpStatus status = result.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
        String message = result.replayed()
                ? "Funds transfer already processed for this idempotency key"
                : "Transfer completed successfully";
        return ResponseEntity.status(status).body(ApiResponse.success(
                message,
                result.payload(),
                httpRequest.getRequestURI()
        ));
    }
}
