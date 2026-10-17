package io.github.amitvishwa.notification.application.replay;

import io.github.amitvishwa.notification.application.exception.ReplayRejectedException;
import io.github.amitvishwa.notification.domain.model.ManualReplayRequest;
import io.github.amitvishwa.notification.domain.model.Notification;
import io.github.amitvishwa.notification.domain.model.NotificationReplay;
import io.github.amitvishwa.notification.domain.model.NotificationStatus;
import io.github.amitvishwa.notification.infrastructure.persistence.DeliveryAttemptRepository;
import io.github.amitvishwa.notification.infrastructure.persistence.NotificationReplayStore;
import io.github.amitvishwa.notification.infrastructure.persistence.NotificationRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;

import static io.github.amitvishwa.notification.application.exception.ReplayRejectedException.Reason.*;

@Service
@Profile("operator-replay")
public class NotificationReplayService {

    private static final String PROCESS = "notification-replay-cli";

    private final NotificationRepository notifications;
    private final DeliveryAttemptRepository attempts;
    private final NotificationReplayStore replays;
    private final Clock clock;

    public NotificationReplayService(
            NotificationRepository notifications,
            DeliveryAttemptRepository attempts,
            NotificationReplayStore replays,
            Clock clock
    ) {
        this.notifications = notifications;
        this.attempts = attempts;
        this.replays = replays;
        this.clock = clock;
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED
    )
    public ManualReplayResult replay(ManualReplayRequest request) {
        Objects.requireNonNull(request, "request must not be null");

        Notification notification = notifications
                .lockById(request.notificationId())
                .orElseThrow(() -> new ReplayRejectedException(NOT_FOUND));

        if (!notification.getSourceApplication().equals(
                request.sourceApplication()
        )) {
            throw new ReplayRejectedException(NOT_FOUND);
        }

        Optional<NotificationReplay> existing =
                replays.findByCommandId(request.commandId());

        if (existing.isPresent()) {
            NotificationReplay previous = existing.get();

            if (!previous.matches(request)) {
                throw new ReplayRejectedException(COMMAND_CONFLICT);
            }

            return result(previous);
        }

        if (notification.getStatus() != NotificationStatus.FAILED) {
            throw new ReplayRejectedException(NOT_FAILED);
        }

        if (notification.getVersion() != request.expectedVersion()) {
            throw new ReplayRejectedException(STALE_VERSION);
        }

        if (attempts.hasOpenAttempts(request.notificationId())) {
            throw new ReplayRejectedException(OPEN_ATTEMPTS);
        }

        String previousFailureCode = notification.getFailureCode();
        int previousCycleCount = notification.getCycleAttemptCount();

        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);

        notification.requeueForManualReplay(PROCESS, now);
        notifications.flush();

        NotificationReplay replay = NotificationReplay.record(
                request,
                notification,
                previousFailureCode,
                previousCycleCount,
                now
        );

        replays.append(replay);

        return result(replay);
    }

    private static ManualReplayResult result(NotificationReplay replay) {
        return new ManualReplayResult(
                replay.getCommandId(),
                replay.getNotificationId(),
                replay.getResultingVersion(),
                replay.getReplayedAt()
        );
    }
}