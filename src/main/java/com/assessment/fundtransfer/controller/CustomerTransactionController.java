package com.assessment.fundtransfer.controller;

import com.assessment.fundtransfer.config.ApiPaths;
import com.assessment.fundtransfer.dto.request.CustomerTransactionRequest;
import com.assessment.fundtransfer.dto.response.ApiResponse;
import com.assessment.fundtransfer.dto.response.BalanceResponse;
import com.assessment.fundtransfer.dto.response.CustomerTransactionResponse;
import com.assessment.fundtransfer.service.CustomerTransactionService;
import com.assessment.fundtransfer.service.IdempotentResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(ApiPaths.CUSTOMER_ACCOUNTS)
@Tag(
        name = "Customer Accounts",
        description = "Operations for creating funded customer accounts and retrieving current account balances."
)
public class CustomerTransactionController {

    private final CustomerTransactionService customerTransactionService;

    public CustomerTransactionController(CustomerTransactionService customerTransactionService) {
        this.customerTransactionService = customerTransactionService;
    }

    @PostMapping
    @Operation(
            summary = "Create funded customer account",
            description = "Creates a customer account with an initial funding amount. Reusing the same Idempotency-Key replays the original response."
    )
    public ResponseEntity<ApiResponse<CustomerTransactionResponse>> saveCustomerTransactionDetails(
            @Parameter(description = "Unique request key used to safely retry the same request")
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CustomerTransactionRequest request,
            HttpServletRequest httpRequest
    ) {
        IdempotentResult<CustomerTransactionResponse> result =
                customerTransactionService.saveCustomerTransactionDetails(request, idempotencyKey);
        HttpStatus status = result.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
        String message = result.replayed()
                ? "Customer account funding already processed for this idempotency key"
                : "Customer account created and funded successfully";
        return ResponseEntity.status(status).body(ApiResponse.success(
                message,
                result.payload(),
                httpRequest.getRequestURI()
        ));
    }

    @GetMapping("/{accountNumber}/balance")
    @Operation(
            summary = "Retrieve current account balance",
            description = "Retrieves the current balance for the requested customer account from the persisted account record."
    )
    public ResponseEntity<ApiResponse<BalanceResponse>> getBalance(
            @PathVariable String accountNumber,
            HttpServletRequest httpRequest
    ) {
        BalanceResponse response = customerTransactionService.getBalance(accountNumber);
        return ResponseEntity.ok(ApiResponse.success(
                "Account balance retrieved successfully",
                response,
                httpRequest.getRequestURI()
        ));
    }
}
