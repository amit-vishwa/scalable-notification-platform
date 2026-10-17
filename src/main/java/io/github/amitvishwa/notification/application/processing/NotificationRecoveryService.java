package io.github.amitvishwa.notification.application.processing;

import io.github.amitvishwa.notification.config.NotificationRetryProperties;
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
import java.util.List;
import java.util.Optional;

@Service
public class NotificationRecoveryService {

    private static final String PROCESS = "notification-recovery";

    private final NotificationRepository notifications;
    private final DeliveryAttemptRepository attempts;
    private final NotificationRetryProperties retryProperties;
    private final Clock clock;

    public NotificationRecoveryService(
            NotificationRepository notifications,
            DeliveryAttemptRepository attempts,
            NotificationRetryProperties retryProperties,
            Clock clock
    ) {
        this.notifications = notifications;
        this.attempts = attempts;
        this.retryProperties = retryProperties;
        this.clock = clock;
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED
    )
    public int recoverExpired(int batchSize) {
        if (batchSize < 1
                || batchSize > NotificationClaimService.MAX_BATCH_SIZE) {
            throw new IllegalArgumentException(
                    "Invalid recovery batch size"
            );
        }

        Instant now = clock.instant();

        List<Notification> expired = notifications.lockExpiredBatch(
                now,
                now.minus(retryProperties.processingLease()),
                batchSize
        );

        for (Notification notification : expired) {
            Optional<DeliveryAttempt> current = attempts.findAttempt(
                    notification.getNotificationId(),
                    notification.getLastAttemptNumber()
            );

            if (current.isPresent()) {
                DeliveryAttempt attempt = current.get();

                if (!attempt.isCompleted()
                        && attempt.getCompletedAtTime() == null) {
                    attempt.completeUnknown(
                            ProcessingFailureCodes.OUTCOME_UNKNOWN,
                            PROCESS,
                            now
                    );
                }
            }

            notification.markFailed(
                    current.isPresent()
                            ? ProcessingFailureCodes.OUTCOME_UNKNOWN
                            : ProcessingFailureCodes.HISTORY_MISSING,
                    PROCESS,
                    now
            );
        }

        notifications.flush();
        return expired.size();
    }
}