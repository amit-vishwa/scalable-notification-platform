package io.github.amitvishwa.notification.application.processing;

import io.github.amitvishwa.notification.application.port.email.EmailProviderResult;
import io.github.amitvishwa.notification.domain.model.DeliveryAttempt;
import io.github.amitvishwa.notification.domain.model.Notification;
import io.github.amitvishwa.notification.domain.model.NotificationStatus;
import io.github.amitvishwa.notification.domain.policy.RetryPolicy;
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
import java.util.Optional;

@Service
public class NotificationOutcomeService {

    private static final String PROCESS = "notification-worker";

    private final NotificationRepository notifications;
    private final DeliveryAttemptRepository attempts;
    private final RetryPolicy retryPolicy;
    private final Clock clock;

    public NotificationOutcomeService(
            NotificationRepository notifications,
            DeliveryAttemptRepository attempts,
            RetryPolicy retryPolicy,
            Clock clock
    ) {
        this.notifications = notifications;
        this.attempts = attempts;
        this.retryPolicy = retryPolicy;
        this.clock = clock;
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED
    )
    public boolean canSend(ClaimedNotification claim) {
        Objects.requireNonNull(claim, "claim must not be null");

        Notification notification = notifications
                .lockById(claim.notificationId())
                .orElse(null);

        if (notification == null) {
            return false;
        }

        Optional<DeliveryAttempt> current =
                currentAttempt(notification, claim);

        if (current.isEmpty()) {
            return false;
        }

        Instant now = clock.instant();

        if (!notification.hasActiveLease(now)) {
            closeUnknown(notification, current.get(), now);
            return false;
        }

        return true;
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED
    )
    public boolean recordOutcome(
            ClaimedNotification claim,
            EmailProviderResult result
    ) {
        Objects.requireNonNull(claim, "claim must not be null");
        Objects.requireNonNull(result, "result must not be null");

        Notification notification = notifications
                .lockById(claim.notificationId())
                .orElse(null);

        if (notification == null) {
            return false;
        }

        Optional<DeliveryAttempt> current =
                currentAttempt(notification, claim);

        if (current.isEmpty()) {
            return false;
        }

        DeliveryAttempt attempt = current.get();
        Instant now = clock.instant();

        if (!notification.hasActiveLease(now)) {
            closeUnknown(notification, attempt, now);
            return false;
        }

        switch (result.outcome()) {
            case ACCEPTED -> {
                attempt.completeAccepted(
                        result.providerMessageId(), PROCESS, now
                );
                notification.markSent(
                        result.providerMessageId(), PROCESS, now
                );
            }

            case TEMPORARY_FAILURE ->
                    recordTemporaryFailure(
                            notification, attempt, result, now
                    );

            case PERMANENT_FAILURE -> {
                attempt.completePermanentFailure(
                        result.failureCode(), PROCESS, now
                );
                notification.markFailed(
                        result.failureCode(), PROCESS, now
                );
            }

            case UNKNOWN -> {
                attempt.completeUnknown(
                        result.failureCode(), PROCESS, now
                );
                notification.markFailed(
                        ProcessingFailureCodes.OUTCOME_UNKNOWN,
                        PROCESS,
                        now
                );
            }
        }

        attempts.flush();
        return true;
    }

    private Optional<DeliveryAttempt> currentAttempt(
            Notification notification,
            ClaimedNotification claim
    ) {
        if (notification.getStatus() != NotificationStatus.PROCESSING) {
            return Optional.empty();
        }

        return attempts.findById(claim.attemptId())
                .filter(attempt ->
                        attempt.getNotificationId().equals(
                                notification.getNotificationId()
                        )
                                && attempt.getAttemptNumber()
                                == notification.getLastAttemptNumber()
                                && !attempt.isCompleted()
                                && attempt.getCompletedAtTime() == null
                );
    }

    private void recordTemporaryFailure(
            Notification notification,
            DeliveryAttempt attempt,
            EmailProviderResult result,
            Instant now
    ) {
        attempt.completeTemporaryFailure(
                result.failureCode(), PROCESS, now
        );

        Optional<Duration> delay = retryPolicy.delayAfter(
                notification.getCycleAttemptCount()
        );

        if (delay.isPresent()) {
            notification.scheduleRetry(
                    result.failureCode(),
                    now.plus(delay.get()),
                    PROCESS,
                    now
            );
        } else {
            notification.markFailed(
                    ProcessingFailureCodes.RETRY_EXHAUSTED,
                    PROCESS,
                    now
            );
        }
    }

    private void closeUnknown(
            Notification notification,
            DeliveryAttempt attempt,
            Instant now
    ) {
        attempt.completeUnknown(
                ProcessingFailureCodes.OUTCOME_UNKNOWN,
                PROCESS,
                now
        );
        notification.markFailed(
                ProcessingFailureCodes.OUTCOME_UNKNOWN,
                PROCESS,
                now
        );

        attempts.flush();
    }
}