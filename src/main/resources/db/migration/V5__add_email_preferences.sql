CREATE TABLE email_preference (
    preference_id BINARY(16) NOT NULL,

    source_application VARCHAR(100)
        CHARACTER SET utf8mb4
        COLLATE utf8mb4_0900_bin NOT NULL,

    recipient_email VARCHAR(254)
        CHARACTER SET utf8mb4
        COLLATE utf8mb4_0900_bin NOT NULL,

    email_enabled BOOLEAN NOT NULL,

    locale_tag VARCHAR(63)
        CHARACTER SET ascii
        COLLATE ascii_bin NOT NULL,

    quiet_start TIME(6) NULL,
    quiet_end TIME(6) NULL,

    time_zone VARCHAR(64)
        CHARACTER SET ascii
        COLLATE ascii_bin NULL,

    version BIGINT NOT NULL DEFAULT 0,

    created_by_process VARCHAR(100) NOT NULL,
    updated_by_process VARCHAR(100) NOT NULL,
    created_at_time DATETIME(6) NOT NULL,
    updated_at_time DATETIME(6) NOT NULL,

    CONSTRAINT pk_email_preference
        PRIMARY KEY (preference_id),

    CONSTRAINT uk_email_preference_owner_recipient
        UNIQUE (source_application, recipient_email),

    CONSTRAINT chk_email_preference_enabled
        CHECK (email_enabled IN (0, 1)),

    CONSTRAINT chk_email_preference_locale
        CHECK (CHAR_LENGTH(locale_tag) BETWEEN 1 AND 63),

    CONSTRAINT chk_email_preference_version
        CHECK (version >= 0),

    CONSTRAINT chk_email_preference_quiet_hours
        CHECK (
            (
                quiet_start IS NULL
                AND quiet_end IS NULL
                AND time_zone IS NULL
            )
            OR
            (
                quiet_start IS NOT NULL
                AND quiet_end IS NOT NULL
                AND time_zone IS NOT NULL
                AND CHAR_LENGTH(time_zone) > 0

                AND quiet_start >= '00:00:00'
                AND quiet_start < '24:00:00'
                AND quiet_end >= '00:00:00'
                AND quiet_end < '24:00:00'

                AND SECOND(quiet_start) = 0
                AND MICROSECOND(quiet_start) = 0
                AND SECOND(quiet_end) = 0
                AND MICROSECOND(quiet_end) = 0

                AND quiet_start <> quiet_end
            )
        )
) ENGINE = InnoDB;


ALTER TABLE notification
    ADD COLUMN suppression_reason VARCHAR(64)
        CHARACTER SET ascii
        COLLATE ascii_bin NULL,

    DROP CHECK chk_notification_status,

    ADD CONSTRAINT chk_notification_status
        CHECK (
            status IN (
                'PENDING',
                'PROCESSING',
                'RETRY_PENDING',
                'SENT',
                'FAILED',
                'SUPPRESSED'
            )
        ),

    ADD CONSTRAINT chk_notification_suppression
        CHECK (
            (
                status = 'SUPPRESSED'
                AND suppression_reason IS NOT NULL
                AND suppression_reason IN (
                    'PREFERENCE_MISSING',
                    'EMAIL_DISABLED'
                )
                AND failure_code IS NULL
                AND provider_message_id IS NULL
                AND processing_expires_at IS NULL
            )
            OR
            (
                status <> 'SUPPRESSED'
                AND suppression_reason IS NULL
            )
        );