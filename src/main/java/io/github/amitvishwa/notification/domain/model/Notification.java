package io.github.amitvishwa.notification.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(
        name = "notification",
        indexes = {
                @Index(
                        name = "idx_notification_worker",
                        columnList = "status, next_attempt_at, " +
                                "created_at_time, notification_id"
                ),
                @Index(
                        name = "idx_notification_owner",
                        columnList = "source_application, notification_id"
                )
        }
)
public class Notification extends AuditableEntity {

    private static final int MAX_SOURCE_APPLICATION_LENGTH = 100;
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 100;
    private static final int MAX_EMAIL_LENGTH = 254;
    private static final int MAX_SUBJECT_LENGTH = 200;
    private static final int MAX_BODY_LENGTH = 10_000;
    private static final int HASH_LENGTH = 64;
    private static final int MAX_FAILURE_CODE_LENGTH = 64;
    private static final int MAX_PROVIDER_MESSAGE_ID_LENGTH = 255;

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(
            name = "notification_id",
            nullable = false,
            columnDefinition = "BINARY(16)"
    )
    private UUID notificationId;

    @Column(
            name = "source_application",
            nullable = false,
            length = MAX_SOURCE_APPLICATION_LENGTH,
            updatable = false
    )
    private String sourceApplication;

    @Column(
            name = "idempotency_key",
            nullable = false,
            length = MAX_IDEMPOTENCY_KEY_LENGTH,
            updatable = false
    )
    private String idempotencyKey;

    @Column(
            name = "request_body_hash",
            nullable = false,
            length = HASH_LENGTH,
            updatable = false,
            columnDefinition = "CHAR(64) CHARACTER SET ascii COLLATE ascii_bin"
    )
    private String requestBodyHash;

    @Column(
            name = "recipient_email",
            nullable = false,
            length = MAX_EMAIL_LENGTH,
            updatable = false
    )
    private String recipientEmail;

    @Column(
            name = "subject",
            nullable = false,
            length = MAX_SUBJECT_LENGTH,
            updatable = false
    )
    private String subject;

    @Column(
            name = "body",
            nullable = false,
            updatable = false,
            columnDefinition = "TEXT"
    )
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private NotificationStatus status;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(
            name = "next_attempt_at",
            nullable = false,
            columnDefinition = "DATETIME(6)"
    )
    private Instant nextAttemptAt;

    @Column(
            name = "last_attempt_at",
            columnDefinition = "DATETIME(6)"
    )
    private Instant lastAttemptAt;

    @Column(name = "failure_code", length = MAX_FAILURE_CODE_LENGTH)
    private String failureCode;

    @Column(
            name = "provider_message_id",
            length = MAX_PROVIDER_MESSAGE_ID_LENGTH
    )
    private String providerMessageId;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected Notification() {
        // Required by JPA.
    }

    private Notification(
            UUID notificationId,
            NotificationKey notificationKey,
            String requestBodyHash,
            String recipientEmail,
            String subject,
            String body,
            String process,
            Instant now
    ) {
        this.notificationId = Objects.requireNonNull(
                notificationId,
                "notificationId must not be null"
        );

        Objects.requireNonNull(
                notificationKey,
                "notificationKey must not be null"
        );

        this.sourceApplication = notificationKey.sourceApplication();
        this.idempotencyKey = notificationKey.idempotencyKey();
        this.requestBodyHash = requireExactLength(
                requestBodyHash,
                "requestBodyHash",
                HASH_LENGTH
        );
        this.recipientEmail = requireText(
                recipientEmail,
                "recipientEmail",
                MAX_EMAIL_LENGTH
        );
        this.subject = requireText(
                subject,
                "subject",
                MAX_SUBJECT_LENGTH
        );
        this.body = requireText(body, "body", MAX_BODY_LENGTH);

        this.status = NotificationStatus.PENDING;
        this.retryCount = 0;
        this.nextAttemptAt = Objects.requireNonNull(
                now,
                "now must not be null"
        );

        initializeAudit(process, now);
    }

    public static Notification create(
            UUID notificationId,
            NotificationKey notificationKey,
            String requestBodyHash,
            String recipientEmail,
            String subject,
            String body,
            String process,
            Instant now
    ) {
        return new Notification(
                notificationId,
                notificationKey,
                requestBodyHash,
                recipientEmail,
                subject,
                body,
                process,
                now
        );
    }

    public void markProcessing(
            String process,
            Instant now
    ) {
        if (status != NotificationStatus.PENDING
                && status != NotificationStatus.RETRY_PENDING) {
            throw invalidTransition(NotificationStatus.PROCESSING);
        }

        status = NotificationStatus.PROCESSING;
        lastAttemptAt = Objects.requireNonNull(
                now,
                "now must not be null"
        );
        failureCode = null;

        updateAudit(process, now);
    }

    public void markSent(
            String providerMessageId,
            String process,
            Instant now
    ) {
        requireStatus(NotificationStatus.PROCESSING);

        this.status = NotificationStatus.SENT;
        this.providerMessageId = requireOptionalText(
                providerMessageId,
                "providerMessageId",
                MAX_PROVIDER_MESSAGE_ID_LENGTH
        );
        this.failureCode = null;

        updateAudit(process, now);
    }

    public void scheduleRetry(
            String failureCode,
            Instant nextAttemptAt,
            String process,
            Instant now
    ) {
        requireStatus(NotificationStatus.PROCESSING);

        Objects.requireNonNull(
                nextAttemptAt,
                "nextAttemptAt must not be null"
        );
        Objects.requireNonNull(now, "now must not be null");

        if (!nextAttemptAt.isAfter(now)) {
            throw new IllegalArgumentException(
                    "nextAttemptAt must be after now"
            );
        }

        this.status = NotificationStatus.RETRY_PENDING;
        this.retryCount++;
        this.nextAttemptAt = nextAttemptAt;
        this.failureCode = requireText(
                failureCode,
                "failureCode",
                MAX_FAILURE_CODE_LENGTH
        );

        updateAudit(process, now);
    }

    public void markFailed(
            String failureCode,
            String process,
            Instant now
    ) {
        requireStatus(NotificationStatus.PROCESSING);

        this.status = NotificationStatus.FAILED;
        this.failureCode = requireText(
                failureCode,
                "failureCode",
                MAX_FAILURE_CODE_LENGTH
        );

        updateAudit(process, now);
    }

    private void requireStatus(NotificationStatus requiredStatus) {
        if (status != requiredStatus) {
            throw invalidTransition(requiredStatus);
        }
    }

    private IllegalStateException invalidTransition(
            NotificationStatus targetStatus
    ) {
        return new IllegalStateException(
                "Cannot transition notification from "
                        + status
                        + " to "
                        + targetStatus
        );
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

    private static String requireExactLength(
            String value,
            String field,
            int requiredLength
    ) {
        Objects.requireNonNull(value, field + " must not be null");

        if (value.length() != requiredLength) {
            throw new IllegalArgumentException(
                    field + " must contain exactly "
                            + requiredLength
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

    public UUID getNotificationId() {
        return notificationId;
    }

    public NotificationKey getNotificationKey() {
        return new NotificationKey(
                sourceApplication,
                idempotencyKey
        );
    }

    public String getSourceApplication() {
        return sourceApplication;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestBodyHash() {
        return requestBodyHash;
    }

    public String getRecipientEmail() {
        return recipientEmail;
    }

    public String getSubject() {
        return subject;
    }

    public String getBody() {
        return body;
    }

    public NotificationStatus getStatus() {
        return status;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public Instant getLastAttemptAt() {
        return lastAttemptAt;
    }

    public String getFailureCode() {
        return failureCode;
    }

    public String getProviderMessageId() {
        return providerMessageId;
    }

    public long getVersion() {
        return version;
    }
}