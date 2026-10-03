package io.github.amitvishwa.notification.application.submission;

import io.github.amitvishwa.notification.application.exception.IdempotencyConflictException;
import io.github.amitvishwa.notification.application.idempotency.RequestFingerprint;
import io.github.amitvishwa.notification.domain.model.Notification;
import io.github.amitvishwa.notification.domain.model.NotificationKey;
import io.github.amitvishwa.notification.infrastructure.persistence.NotificationRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class NotificationSubmissionService {

    private final NotificationRepository notificationRepository;
    private final NotificationCreationService notificationCreationService;

    public NotificationSubmissionService(
            NotificationRepository notificationRepository,
            NotificationCreationService notificationCreationService
    ) {
        this.notificationRepository = notificationRepository;
        this.notificationCreationService = notificationCreationService;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public NotificationSubmissionResult submit(
            NotificationKey notificationKey,
            String recipientEmail,
            String subject,
            String body
    ) {
        String fingerprint = RequestFingerprint.calculate(
                recipientEmail,
                subject,
                body
        );

        Optional<Notification> existing = findExisting(notificationKey);

        if (existing.isPresent()) {
            return resolveExisting(existing.get(), fingerprint);
        }

        Notification created;

        try {
            created = notificationCreationService.create(
                    notificationKey,
                    fingerprint,
                    recipientEmail,
                    subject,
                    body
            );
        } catch (DataIntegrityViolationException exception) {
            // Another request may have committed the same business key.
            Notification winner = findExisting(notificationKey)
                    .orElseThrow(() -> exception);

            return resolveExisting(winner, fingerprint);
        }

        return result(created, true);
    }

    private Optional<Notification> findExisting(
            NotificationKey notificationKey
    ) {
        return notificationRepository
                .findBySourceApplicationAndIdempotencyKey(
                        notificationKey.sourceApplication(),
                        notificationKey.idempotencyKey()
                );
    }

    private NotificationSubmissionResult resolveExisting(
            Notification existing,
            String fingerprint
    ) {
        if (!existing.getRequestBodyHash().equals(fingerprint)) {
            throw new IdempotencyConflictException();
        }

        return result(existing, false);
    }

    private NotificationSubmissionResult result(
            Notification notification,
            boolean created
    ) {
        return new NotificationSubmissionResult(
                notification.getNotificationId(),
                notification.getStatus(),
                created
        );
    }
}