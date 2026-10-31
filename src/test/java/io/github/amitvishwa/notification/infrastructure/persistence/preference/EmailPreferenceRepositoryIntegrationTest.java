package io.github.amitvishwa.notification.infrastructure.persistence.preference;

import io.github.amitvishwa.notification.domain.model.Notification;
import io.github.amitvishwa.notification.domain.model.NotificationKey;
import io.github.amitvishwa.notification.domain.model.NotificationStatus;
import io.github.amitvishwa.notification.domain.model.SuppressionReason;
import io.github.amitvishwa.notification.domain.preference.EmailPreference;
import io.github.amitvishwa.notification.domain.preference.EmailPreferenceSettings;
import io.github.amitvishwa.notification.domain.preference.PreferenceKey;
import io.github.amitvishwa.notification.domain.preference.QuietHours;
import io.github.amitvishwa.notification.infrastructure.persistence.NotificationRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest(properties = {
        "notification.worker.enabled=false",
        "notification.worker.poll-enabled=false",

        "spring.datasource.url="
                + "jdbc:mysql://127.0.0.1:3306/"
                + "notification_platform_test?serverTimezone=UTC",
        "spring.datasource.username=notification_test",
        "spring.datasource.password=${NOTIFICATION_TEST_DB_PASSWORD}",

        "spring.flyway.enabled=true",
        "spring.flyway.url="
                + "jdbc:mysql://127.0.0.1:3306/"
                + "notification_platform_test?serverTimezone=UTC",
        "spring.flyway.user=notification_test",
        "spring.flyway.password=${NOTIFICATION_TEST_DB_PASSWORD}",
        "spring.flyway.schemas=notification_platform_test",
        "spring.flyway.default-schema=notification_platform_test",
        "spring.flyway.create-schemas=false",
        "spring.flyway.locations=classpath:db/migration",
        "spring.flyway.clean-disabled=true",
        "spring.flyway.baseline-on-migrate=false",
        "spring.flyway.out-of-order=false",

        "spring.jpa.hibernate.ddl-auto=validate"
})
@ActiveProfiles("test")
@Transactional
class EmailPreferenceRepositoryIntegrationTest {

    private static final String PROCESS = "preference-db-test";

    private static final Instant NOW =
            Instant.parse("2026-10-31T08:00:00Z");

    private static final EmailPreferenceSettings ENABLED =
            new EmailPreferenceSettings(true, "en-IN", null);

    @Autowired
    private EmailPreferenceRepository preferences;

    @Autowired
    private NotificationRepository notifications;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbc;

    private String sourceApplication;

    @BeforeEach
    void verifyTestDatabaseAndPrepareUniqueOwner() {
        assertThat(jdbc.queryForObject(
                "SELECT DATABASE()",
                String.class
        )).isEqualTo("notification_platform_test");

        assertThat(jdbc.queryForObject(
                "SELECT SUBSTRING_INDEX(CURRENT_USER(), '@', 1)",
                String.class
        )).isEqualTo("notification_test");

        sourceApplication = "p010-db-" + UUID.randomUUID();
    }

    @Test
    void migrationV5WasAppliedSuccessfully() {
        Long successfulMigrations = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM flyway_schema_history
                WHERE version = '5'
                  AND success = 1
                """, Long.class);

        assertThat(successfulMigrations).isEqualTo(1L);
    }

    @Test
    void savesAndReloadsSettingsIdentityAndAuditFields() {
        QuietHours hours = new QuietHours(
                LocalTime.of(22, 0),
                LocalTime.of(7, 0),
                ZoneId.of("Asia/Kolkata")
        );

        EmailPreferenceSettings settings =
                new EmailPreferenceSettings(true, "en-IN", hours);

        EmailPreference saved = savePreference(
                sourceApplication,
                "recipient@example.invalid",
                settings
        );

        UUID id = saved.getPreferenceId();

        assertThat(saved.getVersion()).isZero();

        entityManager.clear();

        EmailPreference reloaded = preferences.findByKey(
                sourceApplication,
                "recipient@example.invalid"
        ).orElseThrow();

        assertThat(reloaded.getPreferenceId()).isEqualTo(id);
        assertThat(reloaded.getPreferenceKey()).isEqualTo(
                new PreferenceKey(
                        sourceApplication,
                        "recipient@example.invalid"
                )
        );
        assertThat(reloaded.getSettings()).isEqualTo(settings);
        assertThat(reloaded.getVersion()).isZero();

        assertThat(reloaded.getCreatedByProcess()).isEqualTo(PROCESS);
        assertThat(reloaded.getUpdatedByProcess()).isEqualTo(PROCESS);
        assertThat(reloaded.getCreatedAtTime()).isEqualTo(NOW);
        assertThat(reloaded.getUpdatedAtTime()).isEqualTo(NOW);

        assertThat(preferences.findOwnedById(id, sourceApplication))
                .isPresent();
    }

    @Test
    void updatesSettingsAndVersionWithoutChangingCreationAudit() {
        EmailPreference saved = savePreference(
                sourceApplication,
                "recipient@example.invalid",
                new EmailPreferenceSettings(
                        true,
                        "en-IN",
                        new QuietHours(
                                LocalTime.of(22, 0),
                                LocalTime.of(7, 0),
                                ZoneId.of("Asia/Kolkata")
                        )
                )
        );

        UUID id = saved.getPreferenceId();
        long initialVersion = saved.getVersion();
        Instant updatedAt = NOW.plusSeconds(30);

        EmailPreferenceSettings changed =
                new EmailPreferenceSettings(false, "hi-IN", null);

        saved.updateSettings(changed, "preference-update-test", updatedAt);

        preferences.flush();
        entityManager.clear();

        EmailPreference reloaded =
                preferences.findById(id).orElseThrow();

        assertThat(reloaded.getSettings()).isEqualTo(changed);
        assertThat(reloaded.getSettings().quietHours()).isNull();
        assertThat(reloaded.getVersion()).isEqualTo(initialVersion + 1);

        assertThat(reloaded.getCreatedByProcess()).isEqualTo(PROCESS);
        assertThat(reloaded.getCreatedAtTime()).isEqualTo(NOW);
        assertThat(reloaded.getUpdatedByProcess())
                .isEqualTo("preference-update-test");
        assertThat(reloaded.getUpdatedAtTime()).isEqualTo(updatedAt);
    }

    @Test
    void businessKeysAreCaseSensitiveAndOwnershipIsEnforced() {
        String upperSource = sourceApplication.toUpperCase(Locale.ROOT);

        UUID firstId = savePreference(
                sourceApplication,
                "Recipient@example.invalid",
                ENABLED
        ).getPreferenceId();

        UUID secondId = savePreference(
                sourceApplication,
                "recipient@example.invalid",
                ENABLED
        ).getPreferenceId();

        UUID thirdId = savePreference(
                upperSource,
                "Recipient@example.invalid",
                ENABLED
        ).getPreferenceId();

        entityManager.clear();

        assertThat(preferences.findByKey(
                sourceApplication,
                "Recipient@example.invalid"
        ).orElseThrow().getPreferenceId()).isEqualTo(firstId);

        assertThat(preferences.findByKey(
                sourceApplication,
                "recipient@example.invalid"
        ).orElseThrow().getPreferenceId()).isEqualTo(secondId);

        assertThat(preferences.findByKey(
                upperSource,
                "Recipient@example.invalid"
        ).orElseThrow().getPreferenceId()).isEqualTo(thirdId);

        assertThat(preferences.findOwnedById(firstId, upperSource))
                .isEmpty();

        assertThat(preferences.findOwnedById(firstId, sourceApplication))
                .isPresent();
    }

    @Test
    void rejectsDuplicateBusinessKeyEvenWithDifferentPreferenceId() {
        savePreference(
                sourceApplication,
                "recipient@example.invalid",
                ENABLED
        );

        EmailPreference duplicate = EmailPreference.create(
                UUID.randomUUID(),
                new PreferenceKey(
                        sourceApplication,
                        "recipient@example.invalid"
                ),
                ENABLED,
                PROCESS,
                NOW
        );

        assertThatThrownBy(() -> preferences.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsAnUpdateUsingAStaleEntityVersion() {
        EmailPreference stale = savePreference(
                sourceApplication,
                "recipient@example.invalid",
                ENABLED
        );

        UUID id = stale.getPreferenceId();

        entityManager.clear();

        EmailPreference current =
                preferences.findById(id).orElseThrow();

        current.updateSettings(
                new EmailPreferenceSettings(false, "hi-IN", null),
                "first-update",
                NOW.plusSeconds(1)
        );

        preferences.flush();
        entityManager.clear();

        stale.updateSettings(
                new EmailPreferenceSettings(true, "fr-FR", null),
                "stale-update",
                NOW.plusSeconds(2)
        );

        assertThatThrownBy(() -> preferences.saveAndFlush(stale))
                .isInstanceOf(OptimisticLockingFailureException.class);
    }

    @ParameterizedTest
    @EnumSource(SuppressionReason.class)
    void persistsSuppressionWithoutCreatingAnAttempt(
            SuppressionReason reason
    ) {
        Notification notification = newNotification();

        notification.suppressBeforeProcessing(reason, PROCESS, NOW);

        UUID id = notifications.saveAndFlush(notification)
                .getNotificationId();

        entityManager.clear();

        Notification reloaded =
                notifications.findById(id).orElseThrow();

        assertThat(reloaded.getStatus())
                .isEqualTo(NotificationStatus.SUPPRESSED);
        assertThat(reloaded.getSuppressionReason()).isEqualTo(reason);
        assertThat(reloaded.getFailureCode()).isNull();
        assertThat(reloaded.getProviderMessageId()).isNull();
        assertThat(reloaded.getProcessingExpiresAt()).isNull();

        assertNoAttemptWasConsumed(reloaded);
    }

    @Test
    void persistsDeferralWithoutCreatingAnAttempt() {
        Notification notification = newNotification();
        Instant eligibleAt = NOW.plusSeconds(3_600);

        notification.deferBeforeProcessing(eligibleAt, PROCESS, NOW);

        UUID id = notifications.saveAndFlush(notification)
                .getNotificationId();

        entityManager.clear();

        Notification reloaded =
                notifications.findById(id).orElseThrow();

        assertThat(reloaded.getStatus())
                .isEqualTo(NotificationStatus.PENDING);
        assertThat(reloaded.getNextAttemptAt()).isEqualTo(eligibleAt);
        assertThat(reloaded.getSuppressionReason()).isNull();

        assertNoAttemptWasConsumed(reloaded);
    }

    @ParameterizedTest
    @MethodSource("invalidQuietHours")
    void databaseRejectsInvalidQuietHours(
            String start,
            String end,
            String zone
    ) {
        UUID id = savePreference(
                sourceApplication,
                "recipient@example.invalid",
                ENABLED
        ).getPreferenceId();

        assertCheckViolation(() -> jdbc.update("""
                UPDATE email_preference
                SET quiet_start = ?,
                    quiet_end = ?,
                    time_zone = ?
                WHERE preference_id = UNHEX(?)
                """, start, end, zone, uuidHex(id)));
    }

    private static Stream<Arguments> invalidQuietHours() {
        return Stream.of(
                Arguments.of("22:00:00", null, "Asia/Kolkata"),
                Arguments.of(null, "07:00:00", "Asia/Kolkata"),
                Arguments.of("22:00:00", "07:00:00", null),
                Arguments.of(null, null, "Asia/Kolkata"),
                Arguments.of("22:00:00", "22:00:00", "Asia/Kolkata"),
                Arguments.of("22:00:01", "07:00:00", "Asia/Kolkata"),
                Arguments.of(
                        "22:00:00.000001",
                        "07:00:00",
                        "Asia/Kolkata"
                ),
                Arguments.of("-01:00:00", "07:00:00", "Asia/Kolkata"),
                Arguments.of("24:00:00", "07:00:00", "Asia/Kolkata")
        );
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 2})
    void databaseRejectsInvalidBooleanValues(int enabled) {
        UUID id = savePreference(
                sourceApplication,
                "recipient@example.invalid",
                ENABLED
        ).getPreferenceId();

        assertCheckViolation(() -> jdbc.update("""
                UPDATE email_preference
                SET email_enabled = ?
                WHERE preference_id = UNHEX(?)
                """, enabled, uuidHex(id)));
    }

    @ParameterizedTest
    @CsvSource({
            "SUPPRESSED,",
            "PENDING,EMAIL_DISABLED",
            "SUPPRESSED,NOT_A_REASON"
    })
    void databaseRejectsInconsistentSuppression(
            String status,
            String reason
    ) {
        UUID id = notifications.saveAndFlush(newNotification())
                .getNotificationId();

        assertCheckViolation(() -> jdbc.update("""
                UPDATE notification
                SET status = ?,
                    suppression_reason = ?
                WHERE notification_id = UNHEX(?)
                """, status, reason, uuidHex(id)));
    }

    private EmailPreference savePreference(
            String owner,
            String recipient,
            EmailPreferenceSettings settings
    ) {
        return preferences.saveAndFlush(
                EmailPreference.create(
                        UUID.randomUUID(),
                        new PreferenceKey(owner, recipient),
                        settings,
                        PROCESS,
                        NOW
                )
        );
    }

    private Notification newNotification() {
        return Notification.create(
                UUID.randomUUID(),
                new NotificationKey(
                        sourceApplication,
                        "p010-" + UUID.randomUUID()
                ),
                "a".repeat(64),
                "recipient@example.invalid",
                "Preference integration test",
                "Synthetic integration-test message.",
                PROCESS,
                NOW
        );
    }

    private void assertNoAttemptWasConsumed(Notification notification) {
        assertThat(notification.getRetryCount()).isZero();
        assertThat(notification.getLastAttemptNumber()).isZero();
        assertThat(notification.getCycleAttemptCount()).isZero();
        assertThat(notification.getLastAttemptAt()).isNull();

        Long attempts = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM delivery_attempt
                WHERE notification_id = UNHEX(?)
                """, Long.class, uuidHex(notification.getNotificationId()));

        assertThat(attempts).isZero();
    }

    private static String uuidHex(UUID id) {
        return id.toString().replace("-", "");
    }

    private static void assertCheckViolation(Runnable action) {
        DataAccessException failure = assertThrows(
                DataAccessException.class,
                action::run
        );

        Throwable cause = failure;

        for (int depth = 0; depth < 16 && cause != null; depth++) {
            if (cause instanceof SQLException sqlException) {
                assertThat(sqlException.getErrorCode()).isEqualTo(3819);
                return;
            }

            cause = cause.getCause();
        }

        throw new AssertionError(
                "Expected MySQL CHECK constraint violation 3819",
                failure
        );
    }
}