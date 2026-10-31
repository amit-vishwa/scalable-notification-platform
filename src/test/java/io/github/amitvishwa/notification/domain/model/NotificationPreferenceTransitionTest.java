package io.github.amitvishwa.notification.domain.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NotificationPreferenceTransitionTest {

    private static final String PROCESS = "preference-test";

    private static final Instant NOW =
            Instant.parse("2026-10-31T08:00:00Z");

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void suppressesWithoutConsumingAnotherAttempt(boolean retryPending) {
        Notification notification =
                retryPending ? retryPending() : create();

        int retries = notification.getRetryCount();
        int attempts = notification.getCycleAttemptCount();
        int lastNumber = notification.getLastAttemptNumber();
        Instant lastAttempt = notification.getLastAttemptAt();
        Instant due = notification.getNextAttemptAt();

        notification.suppressBeforeProcessing(
                SuppressionReason.EMAIL_DISABLED,
                PROCESS,
                due
        );

        assertThat(notification.getStatus())
                .isEqualTo(NotificationStatus.SUPPRESSED);
        assertThat(notification.getSuppressionReason())
                .isEqualTo(SuppressionReason.EMAIL_DISABLED);
        assertThat(notification.getFailureCode()).isNull();
        assertThat(notification.getProviderMessageId()).isNull();
        assertThat(notification.getProcessingExpiresAt()).isNull();
        assertThat(notification.getRetryCount()).isEqualTo(retries);
        assertThat(notification.getCycleAttemptCount()).isEqualTo(attempts);
        assertThat(notification.getLastAttemptNumber()).isEqualTo(lastNumber);
        assertThat(notification.getLastAttemptAt()).isEqualTo(lastAttempt);
        assertThat(notification.getNextAttemptAt()).isEqualTo(due);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void defersWithoutChangingStatusCountersOrFailure(boolean retryPending) {
        Notification notification =
                retryPending ? retryPending() : create();

        NotificationStatus status = notification.getStatus();
        String failure = notification.getFailureCode();
        int retries = notification.getRetryCount();
        int attempts = notification.getCycleAttemptCount();
        int lastNumber = notification.getLastAttemptNumber();
        Instant lastAttempt = notification.getLastAttemptAt();

        Instant due = notification.getNextAttemptAt();
        Instant later = due.plusSeconds(3600);

        notification.deferBeforeProcessing(later, PROCESS, due);

        assertThat(notification.getStatus()).isEqualTo(status);
        assertThat(notification.getNextAttemptAt()).isEqualTo(later);
        assertThat(notification.getFailureCode()).isEqualTo(failure);
        assertThat(notification.getRetryCount()).isEqualTo(retries);
        assertThat(notification.getCycleAttemptCount()).isEqualTo(attempts);
        assertThat(notification.getLastAttemptNumber()).isEqualTo(lastNumber);
        assertThat(notification.getLastAttemptAt()).isEqualTo(lastAttempt);
        assertThat(notification.getSuppressionReason()).isNull();
        assertThat(notification.getProcessingExpiresAt()).isNull();
    }

    @Test
    void rejectsInvalidDecisionsWithoutPartialMutation() {
        Notification notification = create();

        assertThrows(
                IllegalArgumentException.class,
                () -> notification.deferBeforeProcessing(
                        NOW, PROCESS, NOW
                )
        );
        assertThrows(
                NullPointerException.class,
                () -> notification.suppressBeforeProcessing(
                        null, PROCESS, NOW
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> notification.suppressBeforeProcessing(
                        SuppressionReason.PREFERENCE_MISSING,
                        " ",
                        NOW
                )
        );

        assertThat(notification.getStatus())
                .isEqualTo(NotificationStatus.PENDING);
        assertThat(notification.getNextAttemptAt()).isEqualTo(NOW);
        assertThat(notification.getUpdatedAtTime()).isEqualTo(NOW);
        assertThat(notification.getSuppressionReason()).isNull();
        assertThat(notification.getCycleAttemptCount()).isZero();
    }

    @Test
    void doesNotShortenExistingRetryBackoff() {
        Notification notification = retryPending();
        Instant originalDue = notification.getNextAttemptAt();

        assertThrows(
                IllegalStateException.class,
                () -> notification.deferBeforeProcessing(
                        NOW.plusSeconds(30),
                        PROCESS,
                        NOW.plusSeconds(2)
                )
        );

        assertThat(notification.getNextAttemptAt())
                .isEqualTo(originalDue);
        assertThat(notification.getRetryCount()).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(
            value = NotificationStatus.class,
            names = {"PROCESSING", "SENT", "FAILED", "SUPPRESSED"}
    )
    void rejectsPreferenceDecisionsOutsidePendingStates(
            NotificationStatus status
    ) {
        Notification notification = inStatus(status);

        assertThrows(
                IllegalStateException.class,
                () -> notification.suppressBeforeProcessing(
                        SuppressionReason.EMAIL_DISABLED,
                        PROCESS,
                        NOW.plusSeconds(10)
                )
        );
        assertThrows(
                IllegalStateException.class,
                () -> notification.deferBeforeProcessing(
                        NOW.plusSeconds(100),
                        PROCESS,
                        NOW.plusSeconds(10)
                )
        );

        assertThat(notification.getStatus()).isEqualTo(status);
    }

    @Test
    void suppressedNotificationCannotBeClaimedOrReplayed() {
        Notification notification = inStatus(NotificationStatus.SUPPRESSED);

        assertThrows(
                IllegalStateException.class,
                () -> notification.markProcessing(
                        PROCESS,
                        NOW.plusSeconds(1),
                        NOW.plusSeconds(301)
                )
        );
        assertThrows(
                IllegalStateException.class,
                () -> notification.requeueForManualReplay(
                        PROCESS,
                        NOW.plusSeconds(1)
                )
        );
    }

    private static Notification inStatus(NotificationStatus status) {
        Notification notification = create();

        if (status == NotificationStatus.SUPPRESSED) {
            notification.suppressBeforeProcessing(
                    SuppressionReason.EMAIL_DISABLED,
                    PROCESS,
                    NOW
            );
            return notification;
        }

        notification.markProcessing(
                PROCESS, NOW, NOW.plusSeconds(300)
        );

        if (status == NotificationStatus.SENT) {
            notification.markSent(null, PROCESS, NOW);
        } else if (status == NotificationStatus.FAILED) {
            notification.markFailed("TEST_FAILURE", PROCESS, NOW);
        }

        return notification;
    }

    private static Notification retryPending() {
        Notification notification = create();

        notification.markProcessing(
                PROCESS, NOW, NOW.plusSeconds(300)
        );
        notification.scheduleRetry(
                "SMTP_TEMPORARY_REJECTION",
                NOW.plusSeconds(60),
                PROCESS,
                NOW.plusSeconds(1)
        );

        return notification;
    }

    private static Notification create() {
        return Notification.create(
                UUID.randomUUID(),
                new NotificationKey(
                        "employee-service",
                        "p010-" + UUID.randomUUID()
                ),
                "a".repeat(64),
                "recipient@example.invalid",
                "Preference test",
                "Synthetic unit-test message.",
                PROCESS,
                NOW
        );
    }
}