package io.github.amitvishwa.notification.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(
        name = "delivery_attempt",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_delivery_attempt_number",
                        columnNames = {
                                "notification_id",
                                "attempt_number"
                        }
                )
        }
)
public class DeliveryAttempt extends AuditableEntity {

    private static final int MAX_FAILURE_CODE_LENGTH = 64;
    private static final int MAX_PROVIDER_MESSAGE_ID_LENGTH = 255;

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(
            name = "attempt_id",
            nullable = false,
            columnDefinition = "BINARY(16)"
    )
    private UUID attemptId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "notification_id",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "fk_delivery_attempt_notification"
            )
    )
    private Notification notification;

    @Column(name = "attempt_number", nullable = false)
    private int attemptNumber;

    @Column(
            name = "started_at_time",
            nullable = false,
            columnDefinition = "DATETIME(6)"
    )
    private Instant startedAtTime;

    @Column(
            name = "completed_at_time",
            columnDefinition = "DATETIME(6)"
    )
    private Instant completedAtTime;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", length = 32)
    private DeliveryAttemptOutcome outcome;

    @Column(name = "failure_code", length = MAX_FAILURE_CODE_LENGTH)
    private String failureCode;

    @Column(
            name = "provider_message_id",
            length = MAX_PROVIDER_MESSAGE_ID_LENGTH
    )
    private String providerMessageId;

    protected DeliveryAttempt() {
        // Required by JPA.
    }

    private DeliveryAttempt(
            UUID attemptId,
            Notification notification,
            int attemptNumber,
            String process,
            Instant now
    ) {
        this.attemptId = Objects.requireNonNull(
                attemptId,
                "attemptId must not be null"
        );
        this.notification = Objects.requireNonNull(
                notification,
                "notification must not be null"
        );

        if (attemptNumber < 1) {
            throw new IllegalArgumentException(
                    "attemptNumber must be at least 1"
            );
        }

        this.attemptNumber = attemptNumber;
        this.startedAtTime = Objects.requireNonNull(
                now,
                "now must not be null"
        );

        initializeAudit(process, now);
    }

    public static DeliveryAttempt start(
            UUID attemptId,
            Notification notification,
            int attemptNumber,
            String process,
            Instant now
    ) {
        return new DeliveryAttempt(
                attemptId,
                notification,
                attemptNumber,
                process,
                now
        );
    }

    public void completeAccepted(
            String providerMessageId,
            String process,
            Instant now
    ) {
        requireIncomplete();

        this.outcome = DeliveryAttemptOutcome.ACCEPTED;
        this.providerMessageId = requireOptionalText(
                providerMessageId,
                "providerMessageId",
                MAX_PROVIDER_MESSAGE_ID_LENGTH
        );
        this.completedAtTime = requireCompletionTime(now);
        this.failureCode = null;

        updateAudit(process, now);
    }

    public void completeTemporaryFailure(
            String failureCode,
            String process,
            Instant now
    ) {
        completeFailure(
                DeliveryAttemptOutcome.TEMPORARY_FAILURE,
                failureCode,
                process,
                now
        );
    }

    public void completePermanentFailure(
            String failureCode,
            String process,
            Instant now
    ) {
        completeFailure(
                DeliveryAttemptOutcome.PERMANENT_FAILURE,
                failureCode,
                process,
                now
        );
    }

    private void completeFailure(
            DeliveryAttemptOutcome outcome,
            String failureCode,
            String process,
            Instant now
    ) {
        requireIncomplete();

        this.outcome = Objects.requireNonNull(
                outcome,
                "outcome must not be null"
        );
        this.failureCode = requireText(
                failureCode,
                "failureCode",
                MAX_FAILURE_CODE_LENGTH
        );
        this.completedAtTime = requireCompletionTime(now);
        this.providerMessageId = null;

        updateAudit(process, now);
    }

    private Instant requireCompletionTime(Instant now) {
        Objects.requireNonNull(now, "now must not be null");

        if (now.isBefore(startedAtTime)) {
            throw new IllegalArgumentException(
                    "completion time must not be before start time"
            );
        }

        return now;
    }

    private void requireIncomplete() {
        if (outcome != null || completedAtTime != null) {
            throw new IllegalStateException(
                    "Delivery attempt has already been completed"
            );
        }
    }

    private static String requireText(
            String value,
            String field,
            int maxLength
    ) {
        Objects.requireNonNull(value, field + " must not be null");

        if (value.isBlank()) {
            throw new IllegalArgumentException(
                    field + " must not be blank"
            );
        }

        if (value.length() > maxLength) {
            throw new IllegalArgumentException(
                    field + " must not exceed "
                            + maxLength
                            + " characters"
            );
        }

        return value;
    }

    private static String requireOptionalText(
            String value,
            String field,
            int maxLength
    ) {
        if (value == null) {
            return null;
        }

        return requireText(value, field, maxLength);
    }

    public UUID getAttemptId() {
        return attemptId;
    }

    public Notification getNotification() {
        return notification;
    }

    public UUID getNotificationId() {
        return notification.getNotificationId();
    }

    public int getAttemptNumber() {
        return attemptNumber;
    }

    public Instant getStartedAtTime() {
        return startedAtTime;
    }

    public Instant getCompletedAtTime() {
        return completedAtTime;
    }

    public DeliveryAttemptOutcome getOutcome() {
        return outcome;
    }

    public String getFailureCode() {
        return failureCode;
    }

    public String getProviderMessageId() {
        return providerMessageId;
    }

    public boolean isCompleted() {
        return outcome != null;
    }
}