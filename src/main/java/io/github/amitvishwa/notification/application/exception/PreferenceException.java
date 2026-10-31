package io.github.amitvishwa.notification.application.exception;

public final class PreferenceException extends RuntimeException {

    public enum Reason {
        NOT_FOUND,
        ALREADY_EXISTS,
        VERSION_CONFLICT
    }

    private final Reason reason;

    private PreferenceException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public static PreferenceException notFound() {
        return new PreferenceException(
                Reason.NOT_FOUND,
                "Email preference was not found."
        );
    }

    public static PreferenceException alreadyExists() {
        return new PreferenceException(
                Reason.ALREADY_EXISTS,
                "An email preference already exists for this recipient."
        );
    }

    public static PreferenceException versionConflict() {
        return new PreferenceException(
                Reason.VERSION_CONFLICT,
                "The preference has changed. Retrieve it before updating."
        );
    }

    public Reason getReason() {
        return reason;
    }
}