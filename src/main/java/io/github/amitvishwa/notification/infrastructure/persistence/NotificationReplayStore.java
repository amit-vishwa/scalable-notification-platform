package io.github.amitvishwa.notification.infrastructure.persistence;

import io.github.amitvishwa.notification.domain.model.NotificationReplay;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
@Profile("operator-replay")
@Transactional(propagation = Propagation.MANDATORY)
public class NotificationReplayStore {

    private final EntityManager entityManager;

    public NotificationReplayStore(
            EntityManagerFactory entityManagerFactory
    ) {
        this.entityManager =
                SharedEntityManagerCreator.createSharedEntityManager(
                        entityManagerFactory
                );
    }

    public Optional<NotificationReplay> findByCommandId(UUID commandId) {
        Objects.requireNonNull(commandId, "commandId must not be null");

        return Optional.ofNullable(
                entityManager.find(NotificationReplay.class, commandId)
        );
    }

    public void append(NotificationReplay replay) {
        Objects.requireNonNull(replay, "replay must not be null");

        entityManager.persist(replay);
        entityManager.flush();
    }
}