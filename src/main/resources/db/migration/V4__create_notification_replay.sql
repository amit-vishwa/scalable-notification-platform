CREATE TABLE notification_replay (
    command_id BINARY(16) NOT NULL,
    notification_id BINARY(16) NOT NULL,

    source_application VARCHAR(100)
        CHARACTER SET utf8mb4
        COLLATE utf8mb4_0900_bin NOT NULL,

    expected_version BIGINT NOT NULL,
    resulting_version BIGINT NOT NULL,

    previous_failure_code VARCHAR(64) NULL,
    previous_cycle_attempt_count INT NOT NULL,
    last_attempt_number_at_replay INT NOT NULL,

    operator_id VARCHAR(100) NOT NULL,
    investigation_reference VARCHAR(100) NOT NULL,

    duplicate_risk_acknowledged BOOLEAN NOT NULL,
    previous_sender_stopped BOOLEAN NOT NULL,

    created_by_process VARCHAR(100) NOT NULL,
    updated_by_process VARCHAR(100) NOT NULL,
    created_at_time DATETIME(6) NOT NULL,
    updated_at_time DATETIME(6) NOT NULL,

    CONSTRAINT pk_notification_replay
        PRIMARY KEY (command_id),

    CONSTRAINT fk_notification_replay_notification
        FOREIGN KEY (notification_id)
        REFERENCES notification (notification_id)
        ON DELETE RESTRICT,

    CONSTRAINT chk_notification_replay_versions
        CHECK (
            expected_version >= 0
            AND resulting_version > expected_version
        ),

    CONSTRAINT chk_notification_replay_counts
        CHECK (
            previous_cycle_attempt_count >= 0
            AND last_attempt_number_at_replay >= 0
            AND previous_cycle_attempt_count
                <= last_attempt_number_at_replay
        ),

    CONSTRAINT chk_notification_replay_confirmations
        CHECK (
            duplicate_risk_acknowledged = TRUE
            AND previous_sender_stopped = TRUE
        ),

    INDEX idx_notification_replay_history (
        notification_id,
        created_at_time,
        command_id
    )
) ENGINE = InnoDB;