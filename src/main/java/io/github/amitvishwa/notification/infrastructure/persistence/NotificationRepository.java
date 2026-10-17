package io.github.amitvishwa.notification.infrastructure.persistence;

import io.github.amitvishwa.notification.domain.model.Notification;
import io.github.amitvishwa.notification.domain.model.NotificationStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationRepository
        extends JpaRepository<Notification, UUID> {

    Optional<Notification> findBySourceApplicationAndIdempotencyKey(
            String sourceApplication,
            String idempotencyKey
    );

    Optional<Notification> findByNotificationIdAndSourceApplication(
            UUID notificationId,
            String sourceApplication
    );

    @Query("""
            SELECT notification
            FROM Notification notification
            WHERE notification.status IN :statuses
              AND notification.nextAttemptAt <= :eligibleAt
            ORDER BY notification.nextAttemptAt ASC,
                     notification.createdAtTime ASC,
                     notification.notificationId ASC
            """)
    List<Notification> findEligible(
            @Param("statuses")
            Collection<NotificationStatus> statuses,

            @Param("eligibleAt")
            Instant eligibleAt,

            Pageable pageable
    );

    @Query(
            value = """
                SELECT notification.*
                FROM notification
                WHERE notification.status IN (
                    'PENDING', 'RETRY_PENDING'
                )
                  AND notification.next_attempt_at <= :eligibleAt
                ORDER BY notification.next_attempt_at ASC,
                         notification.created_at_time ASC,
                         notification.notification_id ASC
                LIMIT :batchSize
                FOR UPDATE SKIP LOCKED
                """,
            nativeQuery = true
    )
    List<Notification> lockEligibleBatch(
            @Param("eligibleAt") Instant eligibleAt,
            @Param("batchSize") int batchSize
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT notification
        FROM Notification notification
        WHERE notification.notificationId = :notificationId
        """)
    Optional<Notification> lockById(
            @Param("notificationId") UUID notificationId
    );

    @Query(
            value = """
                SELECT notification.*
                FROM notification
                WHERE notification.status = 'PROCESSING'
                  AND (
                      notification.processing_expires_at <= :expiredAt
                      OR (
                          notification.processing_expires_at IS NULL
                          AND (
                              notification.last_attempt_at IS NULL
                              OR notification.last_attempt_at <= :legacyCutoff
                          )
                      )
                  )
                ORDER BY notification.processing_expires_at ASC,
                         notification.notification_id ASC
                LIMIT :batchSize
                FOR UPDATE SKIP LOCKED
                """,
            nativeQuery = true
    )
    List<Notification> lockExpiredBatch(
            @Param("expiredAt") Instant expiredAt,
            @Param("legacyCutoff") Instant legacyCutoff,
            @Param("batchSize") int batchSize
    );
}