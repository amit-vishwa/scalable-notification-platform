package io.github.amitvishwa.notification.domain.model;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

        notification.markProcessing("notification-worker", processingAt);
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

        notification.markProcessing("notification-worker", processingAt);
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
                now.plusSeconds(1)
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