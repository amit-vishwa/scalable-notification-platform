package io.github.amitvishwa.notification.application.processing;

import io.github.amitvishwa.notification.application.port.email.EmailProviderResult;
import io.github.amitvishwa.notification.domain.model.DeliveryAttempt;
import io.github.amitvishwa.notification.domain.model.Notification;
import io.github.amitvishwa.notification.domain.model.NotificationStatus;
import io.github.amitvishwa.notification.infrastructure.persistence.DeliveryAttemptRepository;
import io.github.amitvishwa.notification.infrastructure.persistence.NotificationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

@Service
public class NotificationOutcomeService {

    private static final String WORKER_PROCESS = "notification-worker";
    private static final Duration INITIAL_RETRY_DELAY =
            Duration.ofMinutes(1);

    private final NotificationRepository notificationRepository;
    private final DeliveryAttemptRepository deliveryAttemptRepository;
    private final Clock clock;

    public NotificationOutcomeService(
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
    public void recordOutcome(
            ClaimedNotification claim,
            EmailProviderResult result
    ) {
        Objects.requireNonNull(claim, "claim must not be null");
        Objects.requireNonNull(result, "result must not be null");

        Notification notification = notificationRepository
                .findById(claim.notificationId())
                .orElseThrow(() -> new IllegalStateException(
                        "Claimed notification does not exist"
                ));

        DeliveryAttempt attempt = deliveryAttemptRepository
                .findById(claim.attemptId())
                .orElseThrow(() -> new IllegalStateException(
                        "Claimed delivery attempt does not exist"
                ));

        if (!attempt.getNotificationId()
                .equals(notification.getNotificationId())) {
            throw new IllegalStateException(
                    "Delivery attempt does not belong to notification"
            );
        }

        if (notification.getStatus() != NotificationStatus.PROCESSING) {
            throw new IllegalStateException(
                    "Notification is not processing"
            );
        }

        if (attempt.isCompleted()
                || attempt.getCompletedAtTime() != null) {
            throw new IllegalStateException(
                    "Delivery attempt has already been completed"
            );
        }

        Instant now = clock.instant();

        switch (result.outcome()) {
            case ACCEPTED -> {
                attempt.completeAccepted(
                        result.providerMessageId(),
                        WORKER_PROCESS,
                        now
                );

                notification.markSent(
                        result.providerMessageId(),
                        WORKER_PROCESS,
                        now
                );
            }

            case TEMPORARY_FAILURE -> {
                attempt.completeTemporaryFailure(
                        result.failureCode(),
                        WORKER_PROCESS,
                        now
                );

                notification.scheduleRetry(
                        result.failureCode(),
                        now.plus(INITIAL_RETRY_DELAY),
                        WORKER_PROCESS,
                        now
                );
            }

            case PERMANENT_FAILURE -> {
                attempt.completePermanentFailure(
                        result.failureCode(),
                        WORKER_PROCESS,
                        now
                );

                notification.markFailed(
                        result.failureCode(),
                        WORKER_PROCESS,
                        now
                );
            }

            default -> throw new IllegalStateException(
                    "Unsupported provider outcome"
            );
        }

        deliveryAttemptRepository.flush();
    }
}