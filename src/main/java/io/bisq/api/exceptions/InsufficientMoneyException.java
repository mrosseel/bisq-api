package io.bisq.api.exceptions;

public class InsufficientMoneyException extends Exception {

    public InsufficientMoneyException(String message) {
        super(message);
    }
}
