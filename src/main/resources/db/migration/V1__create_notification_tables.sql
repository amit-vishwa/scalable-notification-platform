CREATE TABLE notification (
    notification_id BINARY(16) NOT NULL,
    source_application VARCHAR(100) NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    request_body_hash CHAR(64)
        CHARACTER SET ascii
        COLLATE ascii_bin NOT NULL,

    recipient_email VARCHAR(254) NOT NULL,
    subject VARCHAR(200) NOT NULL,
    body TEXT NOT NULL,

    status VARCHAR(32) NOT NULL,
    retry_count INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NOT NULL,
    last_attempt_at DATETIME(6) NULL,

    failure_code VARCHAR(64) NULL,
    provider_message_id VARCHAR(255) NULL,

    version BIGINT NOT NULL DEFAULT 0,

    created_by_process VARCHAR(100) NOT NULL,
    updated_by_process VARCHAR(100) NOT NULL,
    created_at_time DATETIME(6) NOT NULL,
    updated_at_time DATETIME(6) NOT NULL,

    CONSTRAINT pk_notification
        PRIMARY KEY (notification_id),

    CONSTRAINT uk_notification_source_idempotency
        UNIQUE (source_application, idempotency_key),

    CONSTRAINT chk_notification_status
        CHECK (
            status IN (
                'PENDING',
                'PROCESSING',
                'RETRY_PENDING',
                'SENT',
                'FAILED'
            )
        ),

    CONSTRAINT chk_notification_retry_count
        CHECK (retry_count >= 0),

    INDEX idx_notification_worker (
        status,
        next_attempt_at,
        created_at_time,
        notification_id
    ),

    INDEX idx_notification_owner (
        source_application,
        notification_id
    )
) ENGINE = InnoDB;


CREATE TABLE delivery_attempt (
    attempt_id BINARY(16) NOT NULL,
    notification_id BINARY(16) NOT NULL,
    attempt_number INT NOT NULL,

    started_at_time DATETIME(6) NOT NULL,
    completed_at_time DATETIME(6) NULL,

    outcome VARCHAR(32) NULL,
    failure_code VARCHAR(64) NULL,
    provider_message_id VARCHAR(255) NULL,

    created_by_process VARCHAR(100) NOT NULL,
    updated_by_process VARCHAR(100) NOT NULL,
    created_at_time DATETIME(6) NOT NULL,
    updated_at_time DATETIME(6) NOT NULL,

    CONSTRAINT pk_delivery_attempt
        PRIMARY KEY (attempt_id),

    CONSTRAINT uk_delivery_attempt_number
        UNIQUE (notification_id, attempt_number),

    CONSTRAINT fk_delivery_attempt_notification
        FOREIGN KEY (notification_id)
        REFERENCES notification (notification_id)
        ON DELETE RESTRICT,

    CONSTRAINT chk_delivery_attempt_number
        CHECK (attempt_number >= 1),

    CONSTRAINT chk_delivery_attempt_outcome
        CHECK (
            outcome IS NULL
            OR outcome IN (
                'ACCEPTED',
                'TEMPORARY_FAILURE',
                'PERMANENT_FAILURE'
            )
        )
) ENGINE = InnoDB;