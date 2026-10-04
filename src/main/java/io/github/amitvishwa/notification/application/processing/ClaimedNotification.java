package io.github.amitvishwa.notification.application.processing;

import io.github.amitvishwa.notification.application.port.email.EmailMessage;

import java.util.Objects;
import java.util.UUID;

public record ClaimedNotification(
        UUID attemptId,
        EmailMessage message
) {

    public ClaimedNotification {
        Objects.requireNonNull(attemptId, "attemptId must not be null");
        Objects.requireNonNull(message, "message must not be null");
    }

    public UUID notificationId() {
        return message.notificationId();
    }
}