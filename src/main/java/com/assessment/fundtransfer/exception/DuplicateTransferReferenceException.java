package com.assessment.fundtransfer.exception;

public class DuplicateTransferReferenceException extends RuntimeException {

    public DuplicateTransferReferenceException(String message) {
        super(message);
    }
}
