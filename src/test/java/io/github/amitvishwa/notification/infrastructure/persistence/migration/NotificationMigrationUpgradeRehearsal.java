package io.github.amitvishwa.notification.infrastructure.persistence.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.parallel.Isolated;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Explicitly invoked, one-shot populated-schema migration rehearsal.
 *
 * Not named *Test: a normal Maven build must not require this extra database.
 * Run only with the dedicated account and an entirely new, empty schema.
 * No Spring context, worker, provider, or operator CLI is started.
 * No automatic cleanup, repair, baseline, or retry is performed.
 */
@EnabledIfEnvironmentVariable(
        named = "NOTIFICATION_UPGRADE_REHEARSAL",
        matches = "true"
)
@Isolated("Uses an exclusively reserved upgrade-rehearsal database")
class NotificationMigrationUpgradeRehearsal {

    private static final String SCHEMA =
            "notification_platform_upgrade_test";
    private static final String USERNAME = "notification_upgrade_test";
    private static final String URL =
            "jdbc:mysql://localhost:3306/" + SCHEMA
                    + "?serverTimezone=UTC"
                    + "&connectTimeout=10000"
                    + "&socketTimeout=30000";

    private static final String NOTIFICATION_SNAPSHOT_SQL = """
            SELECT
                HEX(notification_id) AS notification_id,
                source_application,
                idempotency_key,
                request_body_hash,
                recipient_email,
                subject,
                body,
                status,
                retry_count,
                DATE_FORMAT(next_attempt_at,
                    '%Y-%m-%d %H:%i:%s.%f') AS next_attempt_at,
                DATE_FORMAT(last_attempt_at,
                    '%Y-%m-%d %H:%i:%s.%f') AS last_attempt_at,
                failure_code,
                provider_message_id,
                version,
                created_by_process,
                updated_by_process,
                DATE_FORMAT(created_at_time,
                    '%Y-%m-%d %H:%i:%s.%f') AS created_at_time,
                DATE_FORMAT(updated_at_time,
                    '%Y-%m-%d %H:%i:%s.%f') AS updated_at_time
            FROM notification_platform_upgrade_test.notification
            ORDER BY HEX(notification_id)
            """;

    private static final String ATTEMPT_SNAPSHOT_SQL = """
            SELECT
                HEX(attempt_id) AS attempt_id,
                HEX(notification_id) AS notification_id,
                attempt_number,
                DATE_FORMAT(started_at_time,
                    '%Y-%m-%d %H:%i:%s.%f') AS started_at_time,
                DATE_FORMAT(completed_at_time,
                    '%Y-%m-%d %H:%i:%s.%f') AS completed_at_time,
                outcome,
                failure_code,
                provider_message_id,
                created_by_process,
                updated_by_process,
                DATE_FORMAT(created_at_time,
                    '%Y-%m-%d %H:%i:%s.%f') AS created_at_time,
                DATE_FORMAT(updated_at_time,
                    '%Y-%m-%d %H:%i:%s.%f') AS updated_at_time
            FROM notification_platform_upgrade_test.delivery_attempt
            ORDER BY HEX(attempt_id)
            """;

    @Test
    void preservesPopulatedV2DataWhenMigratingToV4() {
        String password = System.getenv("NOTIFICATION_UPGRADE_DB_PASSWORD");
        assertThat(password != null && !password.isBlank())
                .as("Set the dedicated upgrade-account password without printing it")
                .isTrue();

        DataSource dataSource =
                new DriverManagerDataSource(URL, USERNAME, password);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        assertFreshDedicatedDatabase(jdbc);

        Flyway legacy = flyway(dataSource, "2");
        assertMigrationCount(legacy.migrate(), 2);
        assertVersions(jdbc, "1", "2");
        assertThat(legacy.info().current().getVersion().getVersion())
                .isEqualTo("2");

        seedLegacyData(dataSource, jdbc);

        List<Map<String, Object>> notificationsBefore =
                jdbc.queryForList(NOTIFICATION_SNAPSHOT_SQL);
        List<Map<String, Object>> attemptsBefore =
                jdbc.queryForList(ATTEMPT_SNAPSHOT_SQL);

        assertThat(notificationsBefore).hasSize(4);
        assertThat(attemptsBefore).hasSize(4);

        Flyway upgraded = flyway(dataSource, "4");
        assertMigrationCount(upgraded.migrate(), 2);
        upgraded.validate();
        assertVersions(jdbc, "1", "2", "3", "4");
        assertThat(upgraded.info().current().getVersion().getVersion())
                .isEqualTo("4");
        assertUpgradedData(jdbc, notificationsBefore, attemptsBefore);

        List<Map<String, Object>> historyBeforeRepeat =
                migrationHistory(jdbc);

        assertMigrationCount(upgraded.migrate(), 0);
        upgraded.validate();

        assertUpgradedData(jdbc, notificationsBefore, attemptsBefore);
        assertThat(migrationHistory(jdbc)).isEqualTo(historyBeforeRepeat);
        assertVersions(jdbc, "1", "2", "3", "4");

        System.out.println(
                "UPGRADE_REHEARSAL_OK schema=" + SCHEMA
                        + " oldVersion=2 newVersion=4"
                        + " notifications=4 attempts=4 replayAudits=0"
                        + " repeatMigrations=0"
        );
    }

    private static Flyway flyway(DataSource dataSource, String target) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .defaultSchema(SCHEMA)
                .schemas(SCHEMA)
                .createSchemas(false)
                .cleanDisabled(true)
                .baselineOnMigrate(false)
                .validateOnMigrate(true)
                .outOfOrder(false)
                .failOnMissingLocations(true)
                .target(target)
                .load();
    }

    private static void assertFreshDedicatedDatabase(JdbcTemplate jdbc) {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class))
                .as("Never point this rehearsal at a development or normal test schema")
                .isEqualTo(SCHEMA);
        assertThat(jdbc.queryForObject("SELECT CURRENT_USER()", String.class))
                .as("Use only the newly created, restricted rehearsal account")
                .isEqualTo(USERNAME + "@localhost");

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_schema = ?
                """, Long.class, SCHEMA))
                .as("Schema must be new and empty; never resume by cleaning or repairing")
                .isZero();
    }

    private static void assertMigrationCount(
            MigrateResult result,
            int expected
    ) {
        assertThat(result.success).isTrue();
        assertThat(result.migrationsExecuted).isEqualTo(expected);
    }

    private static void assertVersions(
            JdbcTemplate jdbc,
            String... expected
    ) {
        assertThat(jdbc.queryForList("""
                SELECT version
                FROM notification_platform_upgrade_test.flyway_schema_history
                WHERE success = 1 AND version IS NOT NULL
                ORDER BY installed_rank
                """, String.class))
                .containsExactly(expected);

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM notification_platform_upgrade_test.flyway_schema_history
                WHERE success = 0
                """, Long.class))
                .isZero();
    }

    private static List<Map<String, Object>> migrationHistory(
            JdbcTemplate jdbc
    ) {
        return jdbc.queryForList("""
                SELECT
                    installed_rank,
                    version,
                    description,
                    type,
                    script,
                    checksum,
                    installed_by,
                    DATE_FORMAT(installed_on,
                        '%Y-%m-%d %H:%i:%s.%f') AS installed_on,
                    execution_time,
                    success
                FROM notification_platform_upgrade_test.flyway_schema_history
                ORDER BY installed_rank
                """);
    }

    private static void seedLegacyData(
            DataSource dataSource,
            JdbcTemplate jdbc
    ) {
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource)
        );
        transaction.executeWithoutResult(ignored -> {
            insertNotification(jdbc, 101, "01-pending",
                    "PENDING", 0, null, null);
            // Deliberately unrelated retry_count catches retry_count + 1 backfills.
            insertNotification(jdbc, 102, "02-sparse-history",
                    "RETRY_PENDING", 7, "TEMPORARY_PROVIDER_FAILURE", null);
            insertNotification(jdbc, 103, "03-processing",
                    "PROCESSING", 0, null, null);
            insertNotification(jdbc, 104, "04-sent",
                    "SENT", 0, null, "synthetic-legacy-accepted");

            insertAttempt(jdbc, 1001, 102, 1,
                    "TEMPORARY_FAILURE", "TEMPORARY_ONE", null);
            insertAttempt(jdbc, 1002, 102, 3,
                    "TEMPORARY_FAILURE", "TEMPORARY_THREE", null);
            insertAttempt(jdbc, 1003, 103, 1, null, null, null);
            insertAttempt(jdbc, 1004, 104, 1,
                    "ACCEPTED", null, "synthetic-legacy-accepted");
        });
    }

    private static void insertNotification(
            JdbcTemplate jdbc,
            int id,
            String key,
            String status,
            int retryCount,
            String failureCode,
            String providerMessageId
    ) {
        assertThat(jdbc.update("""
                INSERT INTO notification_platform_upgrade_test.notification (
                    notification_id, source_application, idempotency_key,
                    request_body_hash, recipient_email, subject, body,
                    status, retry_count, next_attempt_at, last_attempt_at,
                    failure_code, provider_message_id, version,
                    created_by_process, updated_by_process,
                    created_at_time, updated_at_time
                ) VALUES (
                    UNHEX(?), 'p008-upgrade-fixture', ?,
                    REPEAT('a', 64), 'nobody@example.invalid', ?,
                    'Synthetic legacy fixture only; no real email was sent.',
                    ?, ?, '2026-10-01 08:03:00.123456',
                    CASE WHEN ? = 'PENDING' THEN NULL
                         ELSE '2026-10-01 08:01:00.123456' END,
                    ?, ?, 42,
                    'p007-upgrade-fixture-create',
                    'p007-upgrade-fixture-update',
                    '2026-10-01 08:00:00.123456',
                    '2026-10-01 08:02:00.654321'
                )
                """,
                hexId(id),
                key,
                "SYNTHETIC upgrade fixture " + key,
                status,
                retryCount,
                status,
                failureCode,
                providerMessageId))
                .isEqualTo(1);
    }

    private static void insertAttempt(
            JdbcTemplate jdbc,
            int id,
            int notificationId,
            int number,
            String outcome,
            String failureCode,
            String providerMessageId
    ) {
        assertThat(jdbc.update("""
                INSERT INTO notification_platform_upgrade_test.delivery_attempt (
                    attempt_id, notification_id, attempt_number,
                    started_at_time, completed_at_time,
                    outcome, failure_code, provider_message_id,
                    created_by_process, updated_by_process,
                    created_at_time, updated_at_time
                ) VALUES (
                    UNHEX(?), UNHEX(?), ?,
                    '2026-10-01 08:01:00.123456',
                    CASE WHEN ? IS NULL THEN NULL
                         ELSE '2026-10-01 08:01:01.654321' END,
                    ?, ?, ?,
                    'p007-upgrade-fixture-create',
                    'p007-upgrade-fixture-update',
                    '2026-10-01 08:01:00.123456',
                    CASE WHEN ? IS NULL
                         THEN '2026-10-01 08:01:00.123456'
                         ELSE '2026-10-01 08:01:01.654321' END
                )
                """,
                hexId(id),
                hexId(notificationId),
                number,
                outcome,
                outcome,
                failureCode,
                providerMessageId,
                outcome))
                .isEqualTo(1);
    }

    private static String hexId(int id) {
        return String.format(Locale.ROOT, "%032x", id);
    }

    private static void assertUpgradedData(
            JdbcTemplate jdbc,
            List<Map<String, Object>> notificationsBefore,
            List<Map<String, Object>> attemptsBefore
    ) {
        assertThat(jdbc.queryForList(NOTIFICATION_SNAPSHOT_SQL))
                .as("Every original notification field must survive unchanged")
                .isEqualTo(notificationsBefore);
        assertThat(jdbc.queryForList(ATTEMPT_SNAPSHOT_SQL))
                .as("Every original attempt and audit field must survive unchanged")
                .isEqualTo(attemptsBefore);

        List<CounterSnapshot> counters = jdbc.query("""
                SELECT
                    idempotency_key,
                    last_attempt_number,
                    cycle_attempt_count,
                    processing_expires_at
                FROM notification_platform_upgrade_test.notification
                ORDER BY idempotency_key
                """, (result, rowNumber) -> new CounterSnapshot(
                        result.getString("idempotency_key"),
                        result.getInt("last_attempt_number"),
                        result.getInt("cycle_attempt_count"),
                        result.getObject("processing_expires_at") == null
                ));

        assertThat(counters).containsExactly(
                new CounterSnapshot("01-pending", 0, 0, true),
                new CounterSnapshot("02-sparse-history", 3, 2, true),
                new CounterSnapshot("03-processing", 1, 1, true),
                new CounterSnapshot("04-sent", 1, 1, true)
        );
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM notification_platform_upgrade_test.notification_replay
                """, Long.class))
                .isZero();
    }

    private record CounterSnapshot(
            String idempotencyKey,
            int lastAttemptNumber,
            int cycleAttemptCount,
            boolean processingLeaseAbsent
    ) {
    }
}
