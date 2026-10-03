package io.github.amitvishwa.notification.application.query;

import io.github.amitvishwa.notification.domain.model.NotificationStatus;

import java.time.Instant;
import java.util.UUID;

public record NotificationStatusResult(
        UUID notificationId,
        String sourceApplication,
        String idempotencyKey,
        String maskedRecipient,
        String subject,
        NotificationStatus status,
        int retryCount,
        Instant nextAttemptAt,
        Instant lastAttemptAt,
        String failureCode,
        Instant createdAtTime,
        Instant updatedAtTime
) {
}