package io.github.amitvishwa.notification.application.replay;

import java.time.Instant;
import java.util.UUID;

/**
 * Snapshot of the committed replay operation, not current delivery status.
 */
public record ManualReplayResult(
        UUID commandId,
        UUID notificationId,
        long requeuedVersion,
        Instant replayedAt
) {
}