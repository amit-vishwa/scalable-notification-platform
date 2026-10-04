package io.github.amitvishwa.notification.application.port.email;

import java.util.Objects;
import java.util.UUID;

public record EmailMessage(
        UUID notificationId,
        String recipientEmail,
        String subject,
        String body
) {

    public EmailMessage {
        Objects.requireNonNull(
                notificationId,
                "notificationId must not be null"
        );
        requireText(recipientEmail, "recipientEmail");
        requireText(subject, "subject");
        requireText(body, "body");
    }

    private static void requireText(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");

        if (value.isBlank()) {
            throw new IllegalArgumentException(
                    field + " must not be blank"
            );
        }
    }

    @Override
    public String toString() {
        return "EmailMessage[notificationId=" + notificationId + "]";
    }
}