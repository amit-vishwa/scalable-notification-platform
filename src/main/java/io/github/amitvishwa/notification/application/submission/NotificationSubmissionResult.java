package io.github.amitvishwa.notification.application.submission;

import io.github.amitvishwa.notification.domain.model.NotificationStatus;

import java.util.UUID;

public record NotificationSubmissionResult(
        UUID notificationId,
        NotificationStatus status,
        boolean created
) {
}