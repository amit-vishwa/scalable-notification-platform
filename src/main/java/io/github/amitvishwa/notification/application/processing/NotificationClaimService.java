package io.github.amitvishwa.notification.application.processing;

import io.github.amitvishwa.notification.application.port.email.EmailMessage;
import io.github.amitvishwa.notification.domain.model.DeliveryAttempt;
import io.github.amitvishwa.notification.domain.model.Notification;
import io.github.amitvishwa.notification.infrastructure.persistence.DeliveryAttemptRepository;
import io.github.amitvishwa.notification.infrastructure.persistence.NotificationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class NotificationClaimService {

    public static final int MAX_BATCH_SIZE = 10;

    private static final int FIRST_ATTEMPT_NUMBER = 1;
    private static final String WORKER_PROCESS = "notification-worker";

    private final NotificationRepository notificationRepository;
    private final DeliveryAttemptRepository deliveryAttemptRepository;
    private final Clock clock;

    public NotificationClaimService(
            NotificationRepository notificationRepository,
            DeliveryAttemptRepository deliveryAttemptRepository,
            Clock clock
    ) {
        this.notificationRepository = notificationRepository;
        this.deliveryAttemptRepository = deliveryAttemptRepository;
        this.clock = clock;
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED
    )
    public List<ClaimedNotification> claimPending(int batchSize) {
        if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException(
                    "batchSize must be between 1 and " + MAX_BATCH_SIZE
            );
        }

        Instant now = clock.instant();

        List<Notification> notifications =
                notificationRepository.lockPendingBatch(now, batchSize);

        if (notifications.isEmpty()) {
            return List.of();
        }

        List<ClaimedNotification> claimed =
                new ArrayList<>(notifications.size());

        for (Notification notification : notifications) {
            notification.markProcessing(WORKER_PROCESS, now);

            DeliveryAttempt attempt = DeliveryAttempt.start(
                    UUID.randomUUID(),
                    notification,
                    FIRST_ATTEMPT_NUMBER,
                    WORKER_PROCESS,
                    now
            );

            DeliveryAttempt savedAttempt =
                    deliveryAttemptRepository.save(attempt);

            EmailMessage message = new EmailMessage(
                    notification.getNotificationId(),
                    notification.getRecipientEmail(),
                    notification.getSubject(),
                    notification.getBody()
            );

            claimed.add(
                    new ClaimedNotification(
                            savedAttempt.getAttemptId(),
                            message
                    )
            );
        }

        deliveryAttemptRepository.flush();

        return List.copyOf(claimed);
    }
}