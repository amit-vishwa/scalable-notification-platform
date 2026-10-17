package io.github.amitvishwa.notification.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Immutable
@Table(name = "notification_replay")
public class NotificationReplay extends AuditableEntity {

    private static final String PROCESS = "notification-replay-cli";

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(
            name = "command_id",
            nullable = false,
            updatable = false,
            columnDefinition = "BINARY(16)"
    )
    private UUID commandId;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(
            name = "notification_id",
            nullable = false,
            updatable = false,
            columnDefinition = "BINARY(16)"
    )
    private UUID notificationId;

    @Column(
            name = "source_application",
            nullable = false,
            updatable = false,
            length = 100
    )
    private String sourceApplication;

    @Column(name = "expected_version", nullable = false, updatable = false)
    private long expectedVersion;

    @Column(name = "resulting_version", nullable = false, updatable = false)
    private long resultingVersion;

    @Column(name = "previous_failure_code", updatable = false, length = 64)
    private String previousFailureCode;

    @Column(
            name = "previous_cycle_attempt_count",
            nullable = false,
            updatable = false
    )
    private int previousCycleAttemptCount;

    @Column(
            name = "last_attempt_number_at_replay",
            nullable = false,
            updatable = false
    )
    private int lastAttemptNumberAtReplay;

    @Column(
            name = "operator_id",
            nullable = false,
            updatable = false,
            length = 100
    )
    private String operatorId;

    @Column(
            name = "investigation_reference",
            nullable = false,
            updatable = false,
            length = 100
    )
    private String investigationReference;

    @Column(
            name = "duplicate_risk_acknowledged",
            nullable = false,
            updatable = false
    )
    private boolean duplicateRiskAcknowledged;

    @Column(
            name = "previous_sender_stopped",
            nullable = false,
            updatable = false
    )
    private boolean previousSenderStopped;

    protected NotificationReplay() {
        // Required by JPA.
    }

    private NotificationReplay(
            ManualReplayRequest request,
            Notification notification,
            String previousFailureCode,
            int previousCycleAttemptCount,
            Instant now
    ) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(
                notification,
                "notification must not be null"
        );
        Objects.requireNonNull(now, "now must not be null");

        if (!notification.getNotificationId().equals(
                request.notificationId()
        ) || !notification.getSourceApplication().equals(
                request.sourceApplication()
        )) {
            throw new IllegalArgumentException(
                    "Replay request does not match the notification"
            );
        }

        if (notification.getStatus() != NotificationStatus.PENDING
                || notification.getVersion()
                != Math.incrementExact(request.expectedVersion())) {
            throw new IllegalStateException(
                    "Replay audit requires a flushed replay transition"
            );
        }

        if (previousCycleAttemptCount < 0
                || previousCycleAttemptCount
                > notification.getLastAttemptNumber()) {
            throw new IllegalArgumentException(
                    "Previous attempt counters are inconsistent"
            );
        }

        if (previousFailureCode != null
                && (previousFailureCode.isBlank()
                || previousFailureCode.length() > 64)) {
            throw new IllegalArgumentException(
                    "Previous failure code is invalid"
            );
        }

        this.commandId = request.commandId();
        this.notificationId = request.notificationId();
        this.sourceApplication = request.sourceApplication();
        this.expectedVersion = request.expectedVersion();
        this.resultingVersion = notification.getVersion();

        this.previousFailureCode = previousFailureCode;
        this.previousCycleAttemptCount = previousCycleAttemptCount;
        this.lastAttemptNumberAtReplay =
                notification.getLastAttemptNumber();

        this.operatorId = request.operatorId();
        this.investigationReference = request.investigationReference();
        this.duplicateRiskAcknowledged =
                request.duplicateRiskAcknowledged();
        this.previousSenderStopped = request.previousSenderStopped();

        initializeAudit(PROCESS, now);
    }

    public static NotificationReplay record(
            ManualReplayRequest request,
            Notification notification,
            String previousFailureCode,
            int previousCycleAttemptCount,
            Instant now
    ) {
        return new NotificationReplay(
                request,
                notification,
                previousFailureCode,
                previousCycleAttemptCount,
                now
        );
    }

    public boolean matches(ManualReplayRequest request) {
        return commandId.equals(request.commandId())
                && notificationId.equals(request.notificationId())
                && sourceApplication.equals(request.sourceApplication())
                && expectedVersion == request.expectedVersion()
                && operatorId.equals(request.operatorId())
                && investigationReference.equals(
                        request.investigationReference()
                )
                && duplicateRiskAcknowledged
                == request.duplicateRiskAcknowledged()
                && previousSenderStopped
                == request.previousSenderStopped();
    }

    public UUID getCommandId() {
        return commandId;
    }

    public UUID getNotificationId() {
        return notificationId;
    }

    public long getResultingVersion() {
        return resultingVersion;
    }

    public Instant getReplayedAt() {
        return getCreatedAtTime();
    }
}