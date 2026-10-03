package io.github.amitvishwa.notification.api.dto;

import io.github.amitvishwa.notification.domain.model.NotificationStatus;

import java.util.UUID;

public record NotificationSubmissionResponse(
        UUID notificationId,
        NotificationStatus status,
        String message,
        String statusUrl
) {
}