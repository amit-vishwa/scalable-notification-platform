ALTER TABLE notification
    MODIFY COLUMN source_application VARCHAR(100)
        CHARACTER SET utf8mb4
        COLLATE utf8mb4_0900_bin NOT NULL,
    MODIFY COLUMN idempotency_key VARCHAR(100)
        CHARACTER SET utf8mb4
        COLLATE utf8mb4_0900_bin NOT NULL;