package io.github.amitvishwa.notification.application.exception;

public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException() {
        super("The idempotency key was already used with different request content.");
    }
}