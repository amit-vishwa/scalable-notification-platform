package io.github.amitvishwa.notification.application.exception;

import java.util.Objects;

public class ReplayRejectedException extends RuntimeException {

    public enum Reason {
        NOT_FOUND,
        COMMAND_CONFLICT,
        NOT_FAILED,
        STALE_VERSION,
        OPEN_ATTEMPTS
    }

    private final Reason reason;

    public ReplayRejectedException(Reason reason) {
        super("Manual replay rejected: "
                + Objects.requireNonNull(reason, "reason must not be null"));
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}