package com.assessment.fundtransfer.service;

public record IdempotentResult<T>(T payload, boolean replayed) {
}
