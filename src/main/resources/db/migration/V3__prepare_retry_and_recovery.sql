ALTER TABLE notification
    ADD COLUMN last_attempt_number INT NOT NULL DEFAULT 0,
    ADD COLUMN cycle_attempt_count INT NOT NULL DEFAULT 0,
    ADD COLUMN processing_expires_at DATETIME(6) NULL,
    ADD INDEX idx_notification_recovery (
        status,
        processing_expires_at,
        notification_id
    );


-- Backfill from persisted attempts, not scheduled retry counts.
-- Do not manufacture attempts or change existing audit information.
UPDATE notification AS notification_record
LEFT JOIN (
    SELECT
        notification_id,
        MAX(attempt_number) AS last_attempt_number,
        COUNT(*) AS cycle_attempt_count
    FROM delivery_attempt
    GROUP BY notification_id
) AS attempt_history
    ON attempt_history.notification_id =
       notification_record.notification_id
SET
    notification_record.last_attempt_number =
        COALESCE(attempt_history.last_attempt_number, 0),
    notification_record.cycle_attempt_count =
        COALESCE(attempt_history.cycle_attempt_count, 0);


ALTER TABLE notification
    ADD CONSTRAINT chk_notification_last_attempt_number
        CHECK (last_attempt_number >= 0),
    ADD CONSTRAINT chk_notification_cycle_attempt_count
        CHECK (
            cycle_attempt_count >= 0
            AND cycle_attempt_count <= last_attempt_number
        );


ALTER TABLE delivery_attempt
    DROP CHECK chk_delivery_attempt_outcome,
    ADD CONSTRAINT chk_delivery_attempt_outcome
        CHECK (
            outcome IS NULL
            OR outcome IN (
                'ACCEPTED',
                'TEMPORARY_FAILURE',
                'PERMANENT_FAILURE',
                'UNKNOWN'
            )
        );