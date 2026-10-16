package com.prabhjot.ledger.domain.exception;

public class UnbalancedTransactionException extends RuntimeException {
    public UnbalancedTransactionException(String message) {
        super(message);
    }
}
