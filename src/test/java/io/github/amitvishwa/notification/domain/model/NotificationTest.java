package io.github.amitvishwa.notification.domain.model;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@ActiveProfiles("test")
class NotificationTest {

    private static final String PROCESS = "notification-api";
    private static final String REQUEST_HASH = "a".repeat(64);

    @Test
    void shouldCreateImmediatelyEligiblePendingNotification() {
        Instant now = Instant.parse("2026-09-30T10:00:00Z");

        Notification notification = createNotification(now);

        assertEquals(NotificationStatus.PENDING, notification.getStatus());
        assertEquals(0, notification.getRetryCount());
        assertEquals(now, notification.getNextAttemptAt());
        assertEquals(now, notification.getCreatedAtTime());
        assertEquals(now, notification.getUpdatedAtTime());
        assertEquals(PROCESS, notification.getCreatedByProcess());
        assertEquals(PROCESS, notification.getUpdatedByProcess());
        assertNull(notification.getLastAttemptAt());
        assertNull(notification.getFailureCode());
    }

    @Test
    void shouldTransitionFromPendingToProcessingToSent() {
        Instant createdAt = Instant.parse("2026-09-30T10:00:00Z");
        Instant processingAt = createdAt.plusSeconds(10);
        Instant sentAt = createdAt.plusSeconds(12);

        Notification notification = createNotification(createdAt);

        notification.markProcessing(
                "notification-worker",
                processingAt,
                processingAt.plusSeconds(300)
        );
        notification.markSent(
                "provider-message-123",
                "notification-worker",
                sentAt
        );

        assertEquals(NotificationStatus.SENT, notification.getStatus());
        assertEquals(processingAt, notification.getLastAttemptAt());
        assertEquals(
                "provider-message-123",
                notification.getProviderMessageId()
        );
        assertNull(notification.getFailureCode());
        assertEquals(sentAt, notification.getUpdatedAtTime());
    }

    @Test
    void shouldScheduleRetryAfterTemporaryFailure() {
        Instant createdAt = Instant.parse("2026-09-30T10:00:00Z");
        Instant processingAt = createdAt.plusSeconds(10);
        Instant failedAt = createdAt.plusSeconds(12);
        Instant nextAttemptAt = createdAt.plusSeconds(60);

        Notification notification = createNotification(createdAt);

        notification.markProcessing(
                "notification-worker",
                processingAt,
                processingAt.plusSeconds(300)
        );
        notification.scheduleRetry(
                "PROVIDER_TIMEOUT",
                nextAttemptAt,
                "notification-worker",
                failedAt
        );

        assertEquals(
                NotificationStatus.RETRY_PENDING,
                notification.getStatus()
        );
        assertEquals(1, notification.getRetryCount());
        assertEquals(nextAttemptAt, notification.getNextAttemptAt());
        assertEquals(
                "PROVIDER_TIMEOUT",
                notification.getFailureCode()
        );
    }

    @Test
    void shouldRejectInvalidTransition() {
        Notification notification = createNotification(
                Instant.parse("2026-09-30T10:00:00Z")
        );

        assertThrows(
                IllegalStateException.class,
                () -> notification.markSent(
                        "provider-message-123",
                        "notification-worker",
                        Instant.parse("2026-09-30T10:00:10Z")
                )
        );
    }

    @Test
    void shouldRejectRetryTimeThatIsNotInFuture() {
        Instant now = Instant.parse("2026-09-30T10:00:00Z");
        Notification notification = createNotification(now);

        notification.markProcessing(
                "notification-worker",
                now.plusSeconds(1),
                now.plusSeconds(301)
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> notification.scheduleRetry(
                        "PROVIDER_TIMEOUT",
                        now.plusSeconds(2),
                        "notification-worker",
                        now.plusSeconds(2)
                )
        );
    }

    @Test
    void shouldCountAttemptsOnlyWhenTheyStart() {
        Instant now = Instant.parse("2026-10-17T09:00:00Z");
        Notification notification = createNotification(now);

        assertEquals(0, notification.getLastAttemptNumber());
        assertEquals(0, notification.getCycleAttemptCount());

        int first = notification.markProcessing(
                "notification-worker",
                now,
                now.plusSeconds(300)
        );

        assertEquals(1, first);
        assertEquals(1, notification.getLastAttemptNumber());
        assertEquals(1, notification.getCycleAttemptCount());

        notification.scheduleRetry(
                "PROVIDER_UNAVAILABLE",
                now.plusSeconds(60),
                "notification-worker",
                now.plusSeconds(1)
        );

        assertEquals(1, notification.getRetryCount());
        assertEquals(1, notification.getLastAttemptNumber());
        assertEquals(1, notification.getCycleAttemptCount());
        assertNull(notification.getProcessingExpiresAt());

        assertThrows(
                IllegalStateException.class,
                () -> notification.markProcessing(
                        "notification-worker",
                        now.plusSeconds(59),
                        now.plusSeconds(359)
                )
        );

        assertEquals(1, notification.getLastAttemptNumber());
        assertEquals(NotificationStatus.RETRY_PENDING, notification.getStatus());

        int second = notification.markProcessing(
                "notification-worker",
                now.plusSeconds(60),
                now.plusSeconds(360)
        );

        assertEquals(2, second);
        assertEquals(2, notification.getLastAttemptNumber());
        assertEquals(2, notification.getCycleAttemptCount());

        assertEquals(REQUEST_HASH, notification.getRequestBodyHash());
        assertEquals("recipient@example.com", notification.getRecipientEmail());
        assertEquals("Order received", notification.getSubject());
        assertEquals("Your order has been received.", notification.getBody());
    }

    @Test
    void shouldExpireLeaseAtItsExactBoundary() {
        Instant now = Instant.parse("2026-10-17T09:00:00Z");
        Notification notification = createNotification(now);

        notification.markProcessing(
                "notification-worker",
                now,
                now.plusSeconds(300)
        );

        assertTrue(notification.hasActiveLease(now.plusSeconds(299)));
        assertFalse(notification.hasActiveLease(now.plusSeconds(300)));

        notification.markFailed(
                "DELIVERY_OUTCOME_UNKNOWN",
                "notification-recovery",
                now.plusSeconds(300)
        );

        assertEquals(NotificationStatus.FAILED, notification.getStatus());
        assertNull(notification.getProcessingExpiresAt());
        assertFalse(notification.hasActiveLease(now.plusSeconds(301)));
    }

    @Test
    void shouldRejectInvalidLeaseWithoutChangingState() {
        Instant now = Instant.parse("2026-10-17T09:00:00Z");
        Notification notification = createNotification(now);

        assertThrows(
                IllegalArgumentException.class,
                () -> notification.markProcessing(
                        "notification-worker",
                        now,
                        now
                )
        );

        assertEquals(NotificationStatus.PENDING, notification.getStatus());
        assertEquals(0, notification.getLastAttemptNumber());
        assertEquals(0, notification.getCycleAttemptCount());
        assertNull(notification.getLastAttemptAt());
        assertNull(notification.getProcessingExpiresAt());
        assertEquals(now, notification.getUpdatedAtTime());
    }

    @Test
    void shouldPreserveLifetimeHistoryWhenManuallyRequeued() {
        Instant now = Instant.parse("2026-10-17T09:00:00Z");
        Notification notification = createNotification(now);

        NotificationKey originalKey = notification.getNotificationKey();

        notification.markProcessing(
                "notification-worker",
                now,
                now.plusSeconds(300)
        );

        notification.scheduleRetry(
                "PROVIDER_UNAVAILABLE",
                now.plusSeconds(60),
                "notification-worker",
                now.plusSeconds(1)
        );

        notification.markProcessing(
                "notification-worker",
                now.plusSeconds(60),
                now.plusSeconds(360)
        );

        notification.markFailed(
                "DELIVERY_OUTCOME_UNKNOWN",
                "notification-recovery",
                now.plusSeconds(61)
        );

        Instant replayAt = now.plusSeconds(62);

        notification.requeueForManualReplay(
                "notification-replay-cli",
                replayAt
        );

        assertEquals(NotificationStatus.PENDING, notification.getStatus());
        assertEquals(replayAt, notification.getNextAttemptAt());
        assertEquals(0, notification.getCycleAttemptCount());

        assertEquals(2, notification.getLastAttemptNumber());
        assertEquals(1, notification.getRetryCount());
        assertEquals(now.plusSeconds(60), notification.getLastAttemptAt());

        assertEquals(originalKey, notification.getNotificationKey());
        assertEquals(REQUEST_HASH, notification.getRequestBodyHash());
        assertEquals("recipient@example.com", notification.getRecipientEmail());
        assertEquals("Order received", notification.getSubject());
        assertEquals("Your order has been received.", notification.getBody());

        assertEquals(now, notification.getCreatedAtTime());
        assertEquals(PROCESS, notification.getCreatedByProcess());
        assertEquals(replayAt, notification.getUpdatedAtTime());

        assertNull(notification.getFailureCode());
        assertNull(notification.getProviderMessageId());
        assertNull(notification.getProcessingExpiresAt());

        assertEquals(
                3,
                notification.markProcessing(
                        "notification-worker",
                        replayAt,
                        replayAt.plusSeconds(300)
                )
        );
        assertEquals(1, notification.getCycleAttemptCount());
    }

    @Test
    void shouldRejectReplayUnlessNotificationHasFailed() {
        Instant now = Instant.parse("2026-10-17T09:00:00Z");
        Notification notification = createNotification(now);

        assertThrows(
                IllegalStateException.class,
                () -> notification.requeueForManualReplay(
                        "notification-replay-cli",
                        now.plusSeconds(1)
                )
        );

        assertEquals(NotificationStatus.PENDING, notification.getStatus());
        assertEquals(now, notification.getUpdatedAtTime());
        assertEquals(0, notification.getLastAttemptNumber());
    }

    private static Notification createNotification(Instant now) {
        return Notification.create(
                UUID.randomUUID(),
                new NotificationKey(
                        "order-service",
                        "order-123"
                ),
                REQUEST_HASH,
                "recipient@example.com",
                "Order received",
                "Your order has been received.",
                PROCESS,
                now
        );
    }
}