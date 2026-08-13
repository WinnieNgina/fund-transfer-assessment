package com.assessment.fundtransfer.exception;

public class DuplicateTransactionReferenceException extends RuntimeException {

    public DuplicateTransactionReferenceException(String message) {
        super(message);
    }
}
