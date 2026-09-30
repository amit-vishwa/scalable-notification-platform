package io.github.amitvishwa.notification.domain.model;

import java.util.Objects;

public record NotificationKey(
        String sourceApplication,
        String idempotencyKey
) {

    private static final int MAX_LENGTH = 100;

    public NotificationKey {
        Objects.requireNonNull(
                sourceApplication,
                "sourceApplication must not be null"
        );
        Objects.requireNonNull(
                idempotencyKey,
                "idempotencyKey must not be null"
        );

        if (sourceApplication.isBlank()) {
            throw new IllegalArgumentException(
                    "sourceApplication must not be blank"
            );
        }

        if (idempotencyKey.isBlank()) {
            throw new IllegalArgumentException(
                    "idempotencyKey must not be blank"
            );
        }

        if (sourceApplication.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "sourceApplication must not exceed 100 characters"
            );
        }

        if (idempotencyKey.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "idempotencyKey must not exceed 100 characters"
            );
        }
    }
}