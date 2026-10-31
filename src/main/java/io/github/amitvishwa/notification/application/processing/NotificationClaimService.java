package io.github.amitvishwa.notification.application.processing;

import io.github.amitvishwa.notification.application.port.email.EmailMessage;
import io.github.amitvishwa.notification.application.preference.PreferenceDecision;
import io.github.amitvishwa.notification.application.preference.PreferenceEvaluationService;
import io.github.amitvishwa.notification.config.NotificationRetryProperties;
import io.github.amitvishwa.notification.domain.model.DeliveryAttempt;
import io.github.amitvishwa.notification.domain.model.Notification;
import io.github.amitvishwa.notification.domain.policy.RetryPolicy;
import io.github.amitvishwa.notification.domain.preference.PreferenceKey;
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

    private static final String PROCESS = "notification-worker";

    private final NotificationRepository notifications;
    private final DeliveryAttemptRepository attempts;
    private final PreferenceEvaluationService preferenceEvaluation;
    private final RetryPolicy retryPolicy;
    private final NotificationRetryProperties retryProperties;
    private final Clock clock;

    public NotificationClaimService(
            NotificationRepository notifications,
            DeliveryAttemptRepository attempts,
            PreferenceEvaluationService preferenceEvaluation,
            RetryPolicy retryPolicy,
            NotificationRetryProperties retryProperties,
            Clock clock
    ) {
        this.notifications = notifications;
        this.attempts = attempts;
        this.preferenceEvaluation = preferenceEvaluation;
        this.retryPolicy = retryPolicy;
        this.retryProperties = retryProperties;
        this.clock = clock;
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED
    )
    public List<ClaimedNotification> claimEligible(int batchSize) {
        if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException(
                    "batchSize must be between 1 and " + MAX_BATCH_SIZE
            );
        }

        Instant now = clock.instant();

        List<Notification> eligible =
                notifications.lockEligibleBatch(now, batchSize);

        List<ClaimedNotification> claims =
                new ArrayList<>(eligible.size());

        for (Notification notification : eligible) {
            if (notification.getCycleAttemptCount()
                    >= retryPolicy.maxAttempts()) {

                notification.failBeforeProcessing(
                        ProcessingFailureCodes.RETRY_EXHAUSTED,
                        PROCESS,
                        now
                );
                continue;
            }

            PreferenceDecision decision = preferenceEvaluation.evaluate(
                    new PreferenceKey(
                            notification.getSourceApplication(),
                            notification.getRecipientEmail()
                    ),
                    now
            );

            if (decision instanceof PreferenceDecision.Suppressed suppressed) {
                notification.suppressBeforeProcessing(
                        suppressed.reason(),
                        PROCESS,
                        now
                );
                continue;
            }

            if (decision instanceof PreferenceDecision.Deferred deferred) {
                notification.deferBeforeProcessing(
                        deferred.nextEligibleAt(),
                        PROCESS,
                        now
                );
                continue;
            }

            // Only an explicit allow decision may create a delivery attempt.
            if (decision != PreferenceDecision.Allowed.INSTANCE) {
                throw new IllegalStateException(
                        "Unsupported preference decision"
                );
            }

            int attemptNumber = notification.markProcessing(
                    PROCESS,
                    now,
                    now.plus(retryProperties.processingLease())
            );

            DeliveryAttempt attempt = DeliveryAttempt.start(
                    UUID.randomUUID(),
                    notification,
                    attemptNumber,
                    PROCESS,
                    now
            );

            DeliveryAttempt savedAttempt = attempts.save(attempt);

            EmailMessage message = new EmailMessage(
                    notification.getNotificationId(),
                    notification.getRecipientEmail(),
                    notification.getSubject(),
                    notification.getBody()
            );

            claims.add(new ClaimedNotification(
                    savedAttempt.getAttemptId(),
                    message
            ));
        }

        // Flushes all managed changes, including suppression and deferral.
        attempts.flush();

        return List.copyOf(claims);
    }
}