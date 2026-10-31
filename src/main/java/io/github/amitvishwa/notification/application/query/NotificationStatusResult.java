package io.github.amitvishwa.notification.application.query;

import io.github.amitvishwa.notification.domain.model.NotificationStatus;
import io.github.amitvishwa.notification.domain.model.SuppressionReason;

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
        SuppressionReason suppressionReason,
        Instant createdAtTime,
        Instant updatedAtTime
) {
}