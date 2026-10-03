package io.github.amitvishwa.notification.application.query;

import io.github.amitvishwa.notification.application.exception.NotificationNotFoundException;
import io.github.amitvishwa.notification.domain.model.Notification;
import io.github.amitvishwa.notification.infrastructure.persistence.NotificationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class NotificationQueryService {

    private final NotificationRepository notificationRepository;

    public NotificationQueryService(
            NotificationRepository notificationRepository
    ) {
        this.notificationRepository = notificationRepository;
    }

    @Transactional(readOnly = true)
    public NotificationStatusResult findStatus(
            UUID notificationId,
            String sourceApplication
    ) {
        Notification notification = notificationRepository
                .findByNotificationIdAndSourceApplication(
                        notificationId,
                        sourceApplication
                )
                .orElseThrow(NotificationNotFoundException::new);

        return new NotificationStatusResult(
                notification.getNotificationId(),
                notification.getSourceApplication(),
                notification.getIdempotencyKey(),
                maskRecipient(notification.getRecipientEmail()),
                notification.getSubject(),
                notification.getStatus(),
                notification.getRetryCount(),
                notification.getNextAttemptAt(),
                notification.getLastAttemptAt(),
                notification.getFailureCode(),
                notification.getCreatedAtTime(),
                notification.getUpdatedAtTime()
        );
    }

    private String maskRecipient(String recipientEmail) {
        int separator = recipientEmail.lastIndexOf('@');

        if (separator < 0) {
            return "***";
        }

        return "***" + recipientEmail.substring(separator);
    }
}