package io.github.amitvishwa.notification.api.error;

public final class InvalidPreferenceRequestException
        extends RuntimeException {

    public InvalidPreferenceRequestException() {
        super("The preference request contains invalid values.");
    }
}