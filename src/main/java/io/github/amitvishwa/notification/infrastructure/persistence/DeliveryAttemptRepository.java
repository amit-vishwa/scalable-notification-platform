package io.github.amitvishwa.notification.infrastructure.persistence;

import io.github.amitvishwa.notification.domain.model.DeliveryAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface DeliveryAttemptRepository
        extends JpaRepository<DeliveryAttempt, UUID> {
}