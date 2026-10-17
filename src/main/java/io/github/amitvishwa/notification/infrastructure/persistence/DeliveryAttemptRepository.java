package io.github.amitvishwa.notification.infrastructure.persistence;

import io.github.amitvishwa.notification.domain.model.DeliveryAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface DeliveryAttemptRepository
        extends JpaRepository<DeliveryAttempt, UUID> {

    @Query("""
            SELECT attempt
            FROM DeliveryAttempt attempt
            WHERE attempt.notification.notificationId = :notificationId
              AND attempt.attemptNumber = :attemptNumber
            """)
    Optional<DeliveryAttempt> findAttempt(
            @Param("notificationId") UUID notificationId,
            @Param("attemptNumber") int attemptNumber
    );

    @Query("""
        SELECT COUNT(attempt) > 0
        FROM DeliveryAttempt attempt
        WHERE attempt.notification.notificationId = :notificationId
          AND (
              attempt.outcome IS NULL
              OR attempt.completedAtTime IS NULL
          )
        """)
    boolean hasOpenAttempts(
            @Param("notificationId") UUID notificationId
    );
}