package io.github.amitvishwa.notification.domain.preference;

import java.util.Objects;

public record PreferenceKey(
        String sourceApplication,
        String recipientEmail
) {

    public PreferenceKey {
        requireText(sourceApplication, "sourceApplication", 100);
        requireText(recipientEmail, "recipientEmail", 254);
    }

    private static void requireText(
            String value,
            String field,
            int maximumLength
    ) {
        Objects.requireNonNull(value, field + " must not be null");

        if (value.isBlank()) {
            throw new IllegalArgumentException(
                    field + " must not be blank"
            );
        }

        if (value.length() > maximumLength) {
            throw new IllegalArgumentException(
                    field + " must not exceed "
                            + maximumLength + " characters"
            );
        }

        if (value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                    field + " must not contain control characters"
            );
        }
    }

    @Override
    public String toString() {
        return "PreferenceKey[sourceApplication=" + sourceApplication
                + ", recipientEmail=<redacted>]";
    }
}