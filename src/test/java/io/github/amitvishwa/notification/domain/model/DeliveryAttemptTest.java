package io.github.amitvishwa.notification.domain.model;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ActiveProfiles("test")
class DeliveryAttemptTest {

    @Test
    void shouldStartIncompleteAttempt() {
        Instant now = Instant.parse("2026-09-30T10:00:00Z");

        DeliveryAttempt attempt = DeliveryAttempt.start(
                UUID.randomUUID(),
                createNotification(now),
                1,
                "notification-worker",
                now
        );

        assertEquals(1, attempt.getAttemptNumber());
        assertEquals(now, attempt.getStartedAtTime());
        assertFalse(attempt.isCompleted());
        assertNull(attempt.getOutcome());
        assertNull(attempt.getCompletedAtTime());
    }

    @Test
    void shouldCompleteAcceptedAttempt() {
        Instant startedAt = Instant.parse("2026-09-30T10:00:00Z");
        Instant completedAt = startedAt.plusSeconds(2);

        DeliveryAttempt attempt = DeliveryAttempt.start(
                UUID.randomUUID(),
                createNotification(startedAt),
                1,
                "notification-worker",
                startedAt
        );

        attempt.completeAccepted(
                "provider-message-123",
                "notification-worker",
                completedAt
        );

        assertTrue(attempt.isCompleted());
        assertEquals(
                DeliveryAttemptOutcome.ACCEPTED,
                attempt.getOutcome()
        );
        assertEquals(completedAt, attempt.getCompletedAtTime());
        assertEquals(
                "provider-message-123",
                attempt.getProviderMessageId()
        );
        assertNull(attempt.getFailureCode());
    }

    @Test
    void shouldCompleteTemporaryFailure() {
        Instant startedAt = Instant.parse("2026-09-30T10:00:00Z");

        DeliveryAttempt attempt = DeliveryAttempt.start(
                UUID.randomUUID(),
                createNotification(startedAt),
                1,
                "notification-worker",
                startedAt
        );

        attempt.completeTemporaryFailure(
                "PROVIDER_TIMEOUT",
                "notification-worker",
                startedAt.plusSeconds(2)
        );

        assertEquals(
                DeliveryAttemptOutcome.TEMPORARY_FAILURE,
                attempt.getOutcome()
        );
        assertEquals(
                "PROVIDER_TIMEOUT",
                attempt.getFailureCode()
        );
        assertNull(attempt.getProviderMessageId());
    }

    @Test
    void shouldRejectCompletingAttemptTwice() {
        Instant startedAt = Instant.parse("2026-09-30T10:00:00Z");

        DeliveryAttempt attempt = DeliveryAttempt.start(
                UUID.randomUUID(),
                createNotification(startedAt),
                1,
                "notification-worker",
                startedAt
        );

        attempt.completePermanentFailure(
                "INVALID_RECIPIENT",
                "notification-worker",
                startedAt.plusSeconds(1)
        );

        assertThrows(
                IllegalStateException.class,
                () -> attempt.completeAccepted(
                        "provider-message-123",
                        "notification-worker",
                        startedAt.plusSeconds(2)
                )
        );
    }

    @Test
    void shouldRejectCompletionBeforeStartTime() {
        Instant startedAt = Instant.parse("2026-09-30T10:00:00Z");

        DeliveryAttempt attempt = DeliveryAttempt.start(
                UUID.randomUUID(),
                createNotification(startedAt),
                1,
                "notification-worker",
                startedAt
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> attempt.completePermanentFailure(
                        "INVALID_RECIPIENT",
                        "notification-worker",
                        startedAt.minusSeconds(1)
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
                "a".repeat(64),
                "recipient@example.com",
                "Order received",
                "Your order has been received.",
                "notification-api",
                now
        );
    }
}