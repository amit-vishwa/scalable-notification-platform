package io.github.amitvishwa.notification.infrastructure.persistence.preference;

import io.github.amitvishwa.notification.application.preference.PreferenceCreationService;
import io.github.amitvishwa.notification.application.preference.PreferenceResult;
import io.github.amitvishwa.notification.domain.preference.EmailPreference;
import io.github.amitvishwa.notification.domain.preference.EmailPreferenceSettings;
import io.github.amitvishwa.notification.domain.preference.PreferenceKey;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Isolated;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "notification.worker.enabled=false",
                "notification.worker.poll-enabled=false",
                "spring.datasource.url=jdbc:mysql://127.0.0.1:3306/"
                        + "notification_platform_test?serverTimezone=UTC",
                "spring.datasource.username=notification_test",
                "spring.datasource.password=${NOTIFICATION_TEST_DB_PASSWORD}",
                "spring.flyway.enabled=true",
                "spring.flyway.url=jdbc:mysql://127.0.0.1:3306/"
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
        }
)
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
@Isolated("Captures database-error logging")
class PreferenceLoggingIntegrationTest {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(PreferenceLoggingIntegrationTest.class);

    private static final String CAPTURE_CONTROL = "P010_CAPTURE_CONTROL";

    @Autowired
    private PreferenceCreationService creationService;

    @Autowired
    private EmailPreferenceRepository preferences;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private JdbcTemplate jdbc;

    private String sourceApplication;
    private String recipientMarker;
    private String recipientEmail;

    @BeforeEach
    void prepareNamespace() {
        assertTestDatabase();

        assertThat(jdbc.queryForObject(
                "SELECT SUBSTRING_INDEX(CURRENT_USER(), '@', 1)",
                String.class
        )).isEqualTo("notification_test");

        String nonce = UUID.randomUUID()
                .toString()
                .replace("-", "")
                .substring(0, 12);

        sourceApplication = "p010log-" + nonce;
        recipientMarker = "p010private-" + nonce;
        recipientEmail = recipientMarker + "@example.invalid";
    }

    @AfterEach
    void cleanOnlyThisTestsPreference() {
        if (sourceApplication == null) {
            return;
        }

        assertTestDatabase();

        jdbc.update("""
                DELETE FROM email_preference
                WHERE source_application = ?
                  AND recipient_email = ?
                """, sourceApplication, recipientEmail);
    }

    @Test
    void duplicateConstraintFailureDoesNotExposeRecipientInLogs(
            CapturedOutput output
    ) {
        assertApplicationLoggingSettingDisabled(
                "hibernate.jdbc.log.errors"
        );
        assertApplicationLoggingSettingDisabled(
                "hibernate.jdbc.log.warnings"
        );

        PreferenceKey key = new PreferenceKey(
                sourceApplication,
                recipientEmail
        );

        EmailPreferenceSettings originalSettings =
                new EmailPreferenceSettings(true, "en-IN", null);

        PreferenceResult created = creationService.create(
                key,
                originalSettings
        );

        // Positive control prevents an inactive capture from passing the test.
        LOGGER.warn(CAPTURE_CONTROL);

        DataIntegrityViolationException failure = assertThrows(
                DataIntegrityViolationException.class,
                () -> creationService.create(
                        key,
                        new EmailPreferenceSettings(false, "hi-IN", null)
                )
        );

        Throwable rootCause = failure.getMostSpecificCause();

        assertThat(rootCause).isInstanceOf(SQLException.class);

        SQLException sqlException = (SQLException) rootCause;

        assertThat(sqlException.getErrorCode()).isEqualTo(1062);
        assertThat(sqlException.getSQLState()).isEqualTo("23000");

        EmailPreference stored = preferences.findByKey(
                sourceApplication,
                recipientEmail
        ).orElseThrow();

        assertThat(stored.getPreferenceId())
                .isEqualTo(created.preferenceId());
        assertThat(stored.getSettings()).isEqualTo(originalSettings);
        assertThat(stored.getVersion()).isZero();

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM email_preference
                WHERE source_application = ?
                  AND recipient_email = ?
                """,
                Long.class,
                sourceApplication,
                recipientEmail
        )).isEqualTo(1L);

        assertThat(output.getAll())
                .contains(CAPTURE_CONTROL)
                .doesNotContain(
                        recipientMarker,
                        recipientEmail,
                        "Duplicate entry"
                );
    }

    private void assertApplicationLoggingSettingDisabled(String setting) {
        Object value = entityManagerFactory.getProperties().get(setting);

        assertThat(value)
                .as("Application setting %s must be configured", setting)
                .isNotNull();

        assertThat(String.valueOf(value))
                .isEqualToIgnoringCase("false");
    }

    private void assertTestDatabase() {
        assertThat(jdbc.queryForObject(
                "SELECT DATABASE()",
                String.class
        )).isEqualTo("notification_platform_test");
    }
}