package io.github.amitvishwa.notification.infrastructure.persistence;

import io.github.amitvishwa.notification.domain.model.Notification;
import io.github.amitvishwa.notification.domain.model.NotificationStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
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
}