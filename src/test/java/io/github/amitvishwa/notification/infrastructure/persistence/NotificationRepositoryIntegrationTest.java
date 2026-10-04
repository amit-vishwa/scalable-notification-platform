package io.github.amitvishwa.notification.infrastructure.persistence;

import io.github.amitvishwa.notification.domain.model.DeliveryAttempt;
import io.github.amitvishwa.notification.domain.model.Notification;
import io.github.amitvishwa.notification.domain.model.NotificationKey;
import io.github.amitvishwa.notification.domain.model.NotificationStatus;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "notification.worker.enabled=false",
        "notification.worker.poll-enabled=false"
})
@ActiveProfiles("test")
@Transactional
class NotificationRepositoryIntegrationTest {

    private static final String PROCESS = "integration-test";
    private static final String REQUEST_HASH = "a".repeat(64);

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private DeliveryAttemptRepository deliveryAttemptRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void savesAndFindsNotificationUsingItsBusinessKeyAndOwner() {
        Instant now = Instant.parse("2026-09-30T08:00:00Z");
        UUID notificationId = UUID.randomUUID();

        Notification notification = createNotification(
                notificationId,
                "employee-service",
                "employee-created-101",
                now
        );

        notificationRepository.saveAndFlush(notification);
        entityManager.clear();

        Notification byBusinessKey = notificationRepository
                .findBySourceApplicationAndIdempotencyKey(
                        "employee-service",
                        "employee-created-101"
                )
                .orElseThrow();

        Notification byOwner = notificationRepository
                .findByNotificationIdAndSourceApplication(
                        notificationId,
                        "employee-service"
                )
                .orElseThrow();

        assertThat(byBusinessKey.getNotificationId())
                .isEqualTo(notificationId);
        assertThat(byBusinessKey.getStatus())
                .isEqualTo(NotificationStatus.PENDING);
        assertThat(byBusinessKey.getCreatedByProcess())
                .isEqualTo(PROCESS);
        assertThat(byOwner.getNotificationId())
                .isEqualTo(notificationId);
    }

    @Test
    void rejectsDuplicateSourceApplicationAndIdempotencyKey() {
        Instant now = Instant.parse("2026-09-30T08:00:00Z");

        notificationRepository.saveAndFlush(
                createNotification(
                        UUID.randomUUID(),
                        "employee-service",
                        "duplicate-key",
                        now
                )
        );

        Notification duplicate = createNotification(
                UUID.randomUUID(),
                "employee-service",
                "duplicate-key",
                now.plusSeconds(1)
        );

        assertThatThrownBy(
                () -> notificationRepository.saveAndFlush(duplicate)
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void findsEligibleNotificationsInDeterministicProcessingOrder() {
        Instant baseTime = Instant.parse("2026-09-30T08:00:00Z");

        Notification olderButScheduledLater = createNotification(
                UUID.fromString("00000000-0000-0000-0000-000000000004"),
                "application-a",
                "key-4",
                baseTime
        );
        olderButScheduledLater.markProcessing(
                PROCESS,
                baseTime.plusSeconds(1)
        );
        olderButScheduledLater.scheduleRetry(
                "TEMPORARY_ERROR",
                baseTime.plusSeconds(30),
                PROCESS,
                baseTime.plusSeconds(2)
        );

        Notification newerButEligibleEarlier = createNotification(
                UUID.fromString("00000000-0000-0000-0000-000000000003"),
                "application-a",
                "key-3",
                baseTime.plusSeconds(10)
        );

        Notification sameTimeFirstId = createNotification(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "application-a",
                "key-1",
                baseTime.plusSeconds(20)
        );

        Notification sameTimeSecondId = createNotification(
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                "application-a",
                "key-2",
                baseTime.plusSeconds(20)
        );

        notificationRepository.saveAllAndFlush(
                List.of(
                        olderButScheduledLater,
                        newerButEligibleEarlier,
                        sameTimeSecondId,
                        sameTimeFirstId
                )
        );
        entityManager.clear();

        List<Notification> eligible =
                notificationRepository.findEligible(
                        List.of(
                                NotificationStatus.PENDING,
                                NotificationStatus.RETRY_PENDING
                        ),
                        baseTime.plusSeconds(30),
                        PageRequest.of(0, 10)
                );

        assertThat(eligible)
                .extracting(Notification::getNotificationId)
                .containsExactly(
                        newerButEligibleEarlier.getNotificationId(),
                        sameTimeFirstId.getNotificationId(),
                        sameTimeSecondId.getNotificationId(),
                        olderButScheduledLater.getNotificationId()
                );
    }

    @Test
    void savesDeliveryAttemptAndRejectsDuplicateAttemptNumber() {
        Instant now = Instant.parse("2026-09-30T08:00:00Z");

        Notification notification = notificationRepository.saveAndFlush(
                createNotification(
                        UUID.randomUUID(),
                        "employee-service",
                        "delivery-attempt-key",
                        now
                )
        );

        DeliveryAttempt firstAttempt = DeliveryAttempt.start(
                UUID.randomUUID(),
                notification,
                1,
                PROCESS,
                now
        );

        deliveryAttemptRepository.saveAndFlush(firstAttempt);

        DeliveryAttempt duplicateAttemptNumber = DeliveryAttempt.start(
                UUID.randomUUID(),
                notification,
                1,
                PROCESS,
                now.plusSeconds(1)
        );

        assertThatThrownBy(
                () -> deliveryAttemptRepository.saveAndFlush(
                        duplicateAttemptNumber
                )
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    private Notification createNotification(
            UUID notificationId,
            String sourceApplication,
            String idempotencyKey,
            Instant now
    ) {
        return Notification.create(
                notificationId,
                new NotificationKey(
                        sourceApplication,
                        idempotencyKey
                ),
                REQUEST_HASH,
                "recipient@example.com",
                "Test notification",
                "Test notification body",
                PROCESS,
                now
        );
    }
}