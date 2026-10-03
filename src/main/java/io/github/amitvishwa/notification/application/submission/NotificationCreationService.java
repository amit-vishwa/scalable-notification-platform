package io.github.amitvishwa.notification.application.submission;

import io.github.amitvishwa.notification.domain.model.Notification;
import io.github.amitvishwa.notification.domain.model.NotificationKey;
import io.github.amitvishwa.notification.infrastructure.persistence.NotificationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

@Service
public class NotificationCreationService {

    private static final String CREATION_PROCESS = "notification-api";

    private final NotificationRepository notificationRepository;
    private final Clock clock;

    public NotificationCreationService(
            NotificationRepository notificationRepository,
            Clock clock
    ) {
        this.notificationRepository = notificationRepository;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Notification create(
            NotificationKey notificationKey,
            String requestBodyHash,
            String recipientEmail,
            String subject,
            String body
    ) {
        Notification notification = Notification.create(
                UUID.randomUUID(),
                notificationKey,
                requestBodyHash,
                recipientEmail,
                subject,
                body,
                CREATION_PROCESS,
                clock.instant()
        );

        return notificationRepository.saveAndFlush(notification);
    }
}