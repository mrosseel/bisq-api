package io.bisq.api.exceptions;

public class AmountTooLowException extends Exception {
    public AmountTooLowException(String message) {
        super(message);
    }
}
