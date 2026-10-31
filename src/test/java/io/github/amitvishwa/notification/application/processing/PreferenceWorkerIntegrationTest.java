package io.github.amitvishwa.notification.application.processing;

import io.github.amitvishwa.notification.application.port.email.EmailMessage;
import io.github.amitvishwa.notification.application.port.email.EmailProvider;
import io.github.amitvishwa.notification.application.port.email.EmailProviderResult;
import io.github.amitvishwa.notification.domain.model.DeliveryAttemptOutcome;
import io.github.amitvishwa.notification.domain.model.Notification;
import io.github.amitvishwa.notification.domain.model.NotificationKey;
import io.github.amitvishwa.notification.domain.model.NotificationStatus;
import io.github.amitvishwa.notification.domain.model.SuppressionReason;
import io.github.amitvishwa.notification.domain.preference.EmailPreference;
import io.github.amitvishwa.notification.domain.preference.EmailPreferenceSettings;
import io.github.amitvishwa.notification.domain.preference.PreferenceKey;
import io.github.amitvishwa.notification.domain.preference.QuietHours;
import io.github.amitvishwa.notification.infrastructure.persistence.DeliveryAttemptRepository;
import io.github.amitvishwa.notification.infrastructure.persistence.NotificationRepository;
import io.github.amitvishwa.notification.infrastructure.persistence.preference.EmailPreferenceRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "notification.worker.enabled=true",
                "notification.worker.poll-enabled=false",
                "notification.email.provider=SIMULATED",
                "notification.retry.max-attempts=5",
                "notification.retry.initial-delay=PT1M",
                "notification.retry.max-delay=PT15M",
                "notification.retry.processing-lease=PT5M",

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
@Execution(ExecutionMode.SAME_THREAD)
@Isolated("Exercises the entire dedicated test queue")
class PreferenceWorkerIntegrationTest {

    private static final Instant NOW =
            Instant.parse("2026-10-31T23:00:00Z");

    private static final Instant QUIET_END =
            Instant.parse("2026-11-01T07:00:00Z");

    private static final String RECIPIENT =
            "recipient@example.invalid";

    private static final String PROCESS = "preference-worker-test";

    private static final String TEMPORARY_FAILURE =
            "SMTP_TEMPORARY_REJECTION";

    @Autowired
    private NotificationBatchProcessor processor;

    @Autowired
    private NotificationClaimService claimService;

    @Autowired
    private NotificationOutcomeService outcomeService;

    @Autowired
    private NotificationRepository notifications;

    @Autowired
    private DeliveryAttemptRepository attempts;

    @Autowired
    private EmailPreferenceRepository preferences;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoBean(enforceOverride = true)
    private EmailProvider emailProvider;

    @MockitoBean(enforceOverride = true)
    private Clock clock;

    private String sourceApplication;
    private TransactionTemplate transactions;

    @BeforeEach
    void prepareFixtures() {
        assertTestDatabase();

        assertThat(jdbc.queryForObject(
                "SELECT SUBSTRING_INDEX(CURRENT_USER(), '@', 1)",
                String.class
        )).isEqualTo("notification_test");

        assertThat(notifications.count())
                .as("Use an empty dedicated notification test queue")
                .isZero();
        assertThat(attempts.count()).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM notification_replay",
                Long.class
        )).isZero();

        transactions = new TransactionTemplate(transactionManager);
        transactions.setIsolationLevel(
                TransactionDefinition.ISOLATION_READ_COMMITTED
        );

        sourceApplication = "p010-worker-" + UUID.randomUUID();

        when(clock.instant()).thenReturn(NOW);
    }

    @AfterEach
    void cleanOnlyThisTestsFixtures() {
        if (sourceApplication == null) {
            return;
        }

        assertTestDatabase();

        transactions.executeWithoutResult(transaction -> {
            jdbc.update("""
                    DELETE FROM notification_replay
                    WHERE notification_id IN (
                        SELECT notification_id
                        FROM notification
                        WHERE source_application = ?
                    )
                    """, sourceApplication);

            jdbc.update("""
                    DELETE FROM delivery_attempt
                    WHERE notification_id IN (
                        SELECT notification_id
                        FROM notification
                        WHERE source_application = ?
                    )
                    """, sourceApplication);

            jdbc.update("""
                    DELETE FROM notification
                    WHERE source_application = ?
                    """, sourceApplication);

            jdbc.update("""
                    DELETE FROM email_preference
                    WHERE source_application = ?
                    """, sourceApplication);
        });
    }

    @ParameterizedTest
    @EnumSource(SuppressionReason.class)
    void suppressesMissingOrDisabledPreferencesWithoutSending(
            SuppressionReason reason
    ) {
        if (reason == SuppressionReason.EMAIL_DISABLED) {
            savePreference(RECIPIENT, false, overnightHours());
        }

        Notification original = savePending(
                "suppressed",
                RECIPIENT,
                NOW.minusSeconds(60)
        );

        processor.processBatch(10);

        Notification stored = reload(original.getNotificationId());

        assertThat(stored.getStatus())
                .isEqualTo(NotificationStatus.SUPPRESSED);
        assertThat(stored.getSuppressionReason()).isEqualTo(reason);
        assertThat(stored.getFailureCode()).isNull();
        assertThat(stored.getProviderMessageId()).isNull();

        assertNeverAttempted(stored);
        verifyNoInteractions(emailProvider);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sendsEnabledPreferencesOutsideQuietHours(
            boolean configureQuietHours
    ) {
        QuietHours hours = configureQuietHours
                ? new QuietHours(
                        LocalTime.of(8, 0),
                        LocalTime.of(9, 0),
                        ZoneId.of("UTC")
                )
                : null;

        savePreference(RECIPIENT, true, hours);

        Notification original = savePending(
                "allowed",
                RECIPIENT,
                NOW.minusSeconds(60)
        );

        when(emailProvider.send(any(EmailMessage.class)))
                .thenReturn(EmailProviderResult.accepted("accepted-1"));

        processor.processBatch(10);

        Notification stored = reload(original.getNotificationId());

        assertThat(stored.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(stored.getSuppressionReason()).isNull();
        assertThat(stored.getProviderMessageId()).isEqualTo("accepted-1");
        assertThat(stored.getRetryCount()).isZero();
        assertThat(stored.getCycleAttemptCount()).isEqualTo(1);
        assertThat(stored.getLastAttemptNumber()).isEqualTo(1);

        assertAttemptCount(stored.getNotificationId(), 1);

        assertThat(attempts.findAttempt(
                stored.getNotificationId(),
                1
        ).orElseThrow().getOutcome())
                .isEqualTo(DeliveryAttemptOutcome.ACCEPTED);

        verify(emailProvider).send(new EmailMessage(
                original.getNotificationId(),
                original.getRecipientEmail(),
                original.getSubject(),
                original.getBody()
        ));
    }

    @Test
    void durablyDefersQuietWorkAndSendsAtTheExactEndBoundary() {
        savePreference(RECIPIENT, true, overnightHours());

        Notification original = savePending(
                "quiet",
                RECIPIENT,
                NOW.minusSeconds(60)
        );

        processor.processBatch(10);

        Notification deferred = reload(original.getNotificationId());

        assertThat(deferred.getStatus())
                .isEqualTo(NotificationStatus.PENDING);
        assertThat(deferred.getNextAttemptAt()).isEqualTo(QUIET_END);

        assertNeverAttempted(deferred);
        verifyNoInteractions(emailProvider);

        when(clock.instant()).thenReturn(QUIET_END.minusSeconds(1));

        processor.processBatch(10);

        assertNeverAttempted(reload(original.getNotificationId()));
        verifyNoInteractions(emailProvider);

        when(clock.instant()).thenReturn(QUIET_END);
        when(emailProvider.send(any(EmailMessage.class)))
                .thenReturn(EmailProviderResult.accepted("quiet-end"));

        processor.processBatch(10);

        Notification sent = reload(original.getNotificationId());

        assertThat(sent.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(sent.getRetryCount()).isZero();
        assertThat(sent.getCycleAttemptCount()).isEqualTo(1);
        assertThat(sent.getLastAttemptAt()).isEqualTo(QUIET_END);

        assertAttemptCount(sent.getNotificationId(), 1);
        verify(emailProvider, times(1)).send(any(EmailMessage.class));
    }

    @Test
    void laterOptInDoesNotResurrectSuppressedWork() {
        Notification original = savePending(
                "no-resurrection",
                RECIPIENT,
                NOW.minusSeconds(60)
        );

        processor.processBatch(10);

        Notification suppressed = reload(original.getNotificationId());

        assertThat(suppressed.getStatus())
                .isEqualTo(NotificationStatus.SUPPRESSED);
        assertThat(suppressed.getSuppressionReason())
                .isEqualTo(SuppressionReason.PREFERENCE_MISSING);

        long suppressedVersion = suppressed.getVersion();

        when(clock.instant()).thenReturn(NOW.plusSeconds(30));
        savePreference(RECIPIENT, true, null);

        processor.processBatch(10);

        Notification unchanged = reload(original.getNotificationId());

        assertThat(unchanged.getStatus())
                .isEqualTo(NotificationStatus.SUPPRESSED);
        assertThat(unchanged.getVersion()).isEqualTo(suppressedVersion);

        assertNeverAttempted(unchanged);
        verifyNoInteractions(emailProvider);
    }

    @Test
    void optOutBeforeRetrySuppressesWithoutErasingAttemptHistory() {
        EmailPreference preference =
                savePreference(RECIPIENT, true, null);

        Notification original = savePending(
                "retry-optout",
                RECIPIENT,
                NOW.minusSeconds(60)
        );

        when(emailProvider.send(any(EmailMessage.class)))
                .thenReturn(
                        EmailProviderResult.temporaryFailure(TEMPORARY_FAILURE)
                );

        processor.processBatch(10);

        Notification retry = reload(original.getNotificationId());

        assertThat(retry.getStatus())
                .isEqualTo(NotificationStatus.RETRY_PENDING);

        Instant retryAt = retry.getNextAttemptAt();

        when(clock.instant()).thenReturn(NOW.plusSeconds(30));
        changePreference(preference.getPreferenceId(), false, null);

        when(clock.instant()).thenReturn(retryAt);
        processor.processBatch(10);

        Notification suppressed = reload(original.getNotificationId());

        assertThat(suppressed.getStatus())
                .isEqualTo(NotificationStatus.SUPPRESSED);
        assertThat(suppressed.getSuppressionReason())
                .isEqualTo(SuppressionReason.EMAIL_DISABLED);
        assertThat(suppressed.getFailureCode()).isNull();
        assertThat(suppressed.getRetryCount()).isEqualTo(1);
        assertThat(suppressed.getCycleAttemptCount()).isEqualTo(1);
        assertThat(suppressed.getLastAttemptNumber()).isEqualTo(1);
        assertThat(suppressed.getLastAttemptAt()).isEqualTo(NOW);

        assertAttemptCount(suppressed.getNotificationId(), 1);

        assertThat(attempts.findAttempt(
                suppressed.getNotificationId(),
                1
        ).orElseThrow().getFailureCode()).isEqualTo(TEMPORARY_FAILURE);

        verify(emailProvider, times(1)).send(any(EmailMessage.class));
    }

    @Test
    void quietHoursPreserveRetryBackoffCountersAndFailureHistory() {
        EmailPreference preference =
                savePreference(RECIPIENT, true, null);

        Notification original = savePending(
                "retry-quiet",
                RECIPIENT,
                NOW.minusSeconds(60)
        );

        when(emailProvider.send(any(EmailMessage.class)))
                .thenReturn(
                        EmailProviderResult.temporaryFailure(TEMPORARY_FAILURE),
                        EmailProviderResult.accepted("retry-accepted")
                );

        processor.processBatch(10);

        Notification retry = reload(original.getNotificationId());
        Instant retryAt = retry.getNextAttemptAt();
        long retryVersion = retry.getVersion();

        assertThat(retryAt).isEqualTo(NOW.plusSeconds(60));

        when(clock.instant()).thenReturn(NOW.plusSeconds(30));

        changePreference(
                preference.getPreferenceId(),
                true,
                overnightHours()
        );

        processor.processBatch(10);

        Notification notYetDue = reload(original.getNotificationId());

        assertThat(notYetDue.getNextAttemptAt()).isEqualTo(retryAt);
        assertThat(notYetDue.getVersion()).isEqualTo(retryVersion);

        when(clock.instant()).thenReturn(retryAt);
        processor.processBatch(10);

        Notification deferred = reload(original.getNotificationId());

        assertThat(deferred.getStatus())
                .isEqualTo(NotificationStatus.RETRY_PENDING);
        assertThat(deferred.getNextAttemptAt()).isEqualTo(QUIET_END);
        assertThat(deferred.getRetryCount()).isEqualTo(1);
        assertThat(deferred.getCycleAttemptCount()).isEqualTo(1);
        assertThat(deferred.getLastAttemptNumber()).isEqualTo(1);
        assertThat(deferred.getLastAttemptAt()).isEqualTo(NOW);
        assertThat(deferred.getFailureCode()).isEqualTo(TEMPORARY_FAILURE);

        assertAttemptCount(deferred.getNotificationId(), 1);
        verify(emailProvider, times(1)).send(any(EmailMessage.class));

        when(clock.instant()).thenReturn(QUIET_END);
        processor.processBatch(10);

        Notification sent = reload(original.getNotificationId());

        assertThat(sent.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(sent.getRetryCount()).isEqualTo(1);
        assertThat(sent.getCycleAttemptCount()).isEqualTo(2);
        assertThat(sent.getLastAttemptNumber()).isEqualTo(2);
        assertThat(sent.getFailureCode()).isNull();

        assertAttemptCount(sent.getNotificationId(), 2);

        assertThat(attempts.findAttempt(
                sent.getNotificationId(),
                1
        ).orElseThrow().getOutcome())
                .isEqualTo(DeliveryAttemptOutcome.TEMPORARY_FAILURE);

        assertThat(attempts.findAttempt(
                sent.getNotificationId(),
                2
        ).orElseThrow().getOutcome())
                .isEqualTo(DeliveryAttemptOutcome.ACCEPTED);

        verify(emailProvider, times(2)).send(any(EmailMessage.class));
    }

    @Test
    void preferenceChangesAffectLaterClaimsNotAlreadyClaimedWork() {
        EmailPreference preference =
                savePreference(RECIPIENT, true, null);

        Notification original = savePending(
                "already-claimed",
                RECIPIENT,
                NOW.minusSeconds(60)
        );

        List<ClaimedNotification> claims = claimService.claimEligible(1);
        assertThat(claims).hasSize(1);

        ClaimedNotification claim = claims.get(0);

        when(clock.instant()).thenReturn(NOW.plusSeconds(1));
        changePreference(preference.getPreferenceId(), false, null);

        assertThat(outcomeService.canSend(claim)).isTrue();

        EmailProviderResult accepted =
                EmailProviderResult.accepted("claim-snapshot");

        when(emailProvider.send(claim.message())).thenReturn(accepted);

        assertThat(outcomeService.recordOutcome(
                claim,
                emailProvider.send(claim.message())
        )).isTrue();

        assertThat(reload(original.getNotificationId()).getStatus())
                .isEqualTo(NotificationStatus.SENT);

        Notification later = savePending(
                "after-optout",
                RECIPIENT,
                NOW.plusSeconds(1)
        );

        processor.processBatch(10);

        Notification suppressed = reload(later.getNotificationId());

        assertThat(suppressed.getStatus())
                .isEqualTo(NotificationStatus.SUPPRESSED);
        assertThat(suppressed.getSuppressionReason())
                .isEqualTo(SuppressionReason.EMAIL_DISABLED);

        assertNeverAttempted(suppressed);
        verify(emailProvider, times(1)).send(any(EmailMessage.class));
    }

    @Test
    void caseDifferentPreferenceDoesNotAuthorizeAnotherRecipient() {
        savePreference("Recipient@example.invalid", true, null);

        Notification original = savePending(
                "case-distinct",
                RECIPIENT,
                NOW.minusSeconds(60)
        );

        processor.processBatch(10);

        Notification stored = reload(original.getNotificationId());

        assertThat(stored.getStatus())
                .isEqualTo(NotificationStatus.SUPPRESSED);
        assertThat(stored.getSuppressionReason())
                .isEqualTo(SuppressionReason.PREFERENCE_MISSING);

        assertNeverAttempted(stored);
        verifyNoInteractions(emailProvider);
    }

    @Test
    void doesNotCommitEarlierSuppressionWhenLaterPreferenceIsInvalid() {
        String brokenRecipient = "broken@example.invalid";

        EmailPreference broken =
                savePreference(brokenRecipient, true, overnightHours());

        int changed = jdbc.update("""
                UPDATE email_preference
                SET time_zone = ?
                WHERE preference_id = UNHEX(?)
                  AND source_application = ?
                """,
                "Invalid/Zone",
                uuidHex(broken.getPreferenceId()),
                sourceApplication
        );

        assertThat(changed).isEqualTo(1);

        Notification first = savePending(
                "rollback-first",
                "missing@example.invalid",
                NOW.minusSeconds(60)
        );

        Notification second = savePending(
                "rollback-second",
                brokenRecipient,
                NOW.minusSeconds(30)
        );

        assertThatThrownBy(() -> claimService.claimEligible(2))
                .isInstanceOf(DateTimeException.class);

        for (Notification original : List.of(first, second)) {
            Notification stored = reload(original.getNotificationId());

            assertThat(stored.getStatus())
                    .isEqualTo(NotificationStatus.PENDING);
            assertThat(stored.getSuppressionReason()).isNull();
            assertThat(stored.getVersion())
                    .isEqualTo(original.getVersion());
            assertThat(stored.getUpdatedAtTime())
                    .isEqualTo(original.getUpdatedAtTime());
            assertThat(stored.getUpdatedByProcess())
                    .isEqualTo(original.getUpdatedByProcess());
            assertThat(stored.getNextAttemptAt())
                    .isEqualTo(original.getNextAttemptAt());

            assertNeverAttempted(stored);
        }

        verifyNoInteractions(emailProvider);
    }

    private EmailPreference savePreference(
            String recipient,
            boolean enabled,
            QuietHours hours
    ) {
        return preferences.saveAndFlush(EmailPreference.create(
                UUID.randomUUID(),
                new PreferenceKey(sourceApplication, recipient),
                new EmailPreferenceSettings(enabled, "en-IN", hours),
                PROCESS,
                clock.instant()
        ));
    }

    private void changePreference(
            UUID preferenceId,
            boolean enabled,
            QuietHours hours
    ) {
        transactions.executeWithoutResult(transaction -> {
            EmailPreference preference = preferences
                    .findById(preferenceId)
                    .orElseThrow();

            preference.updateSettings(
                    new EmailPreferenceSettings(enabled, "en-IN", hours),
                    PROCESS,
                    clock.instant()
            );

            preferences.flush();
        });
    }

    private Notification savePending(
            String label,
            String recipient,
            Instant createdAt
    ) {
        return notifications.saveAndFlush(Notification.create(
                UUID.randomUUID(),
                new NotificationKey(sourceApplication, "key-" + label),
                "a".repeat(64),
                recipient,
                "Preference worker test",
                "Synthetic worker integration-test message.",
                PROCESS,
                createdAt
        ));
    }

    private Notification reload(UUID notificationId) {
        return notifications.findById(notificationId).orElseThrow();
    }

    private void assertNeverAttempted(Notification notification) {
        assertThat(notification.getRetryCount()).isZero();
        assertThat(notification.getCycleAttemptCount()).isZero();
        assertThat(notification.getLastAttemptNumber()).isZero();
        assertThat(notification.getLastAttemptAt()).isNull();
        assertThat(notification.getProcessingExpiresAt()).isNull();

        assertAttemptCount(notification.getNotificationId(), 0);
    }

    private void assertAttemptCount(UUID notificationId, long expected) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM delivery_attempt
                WHERE notification_id = UNHEX(?)
                """,
                Long.class,
                uuidHex(notificationId)
        );

        assertThat(count).isEqualTo(expected);
    }

    private void assertTestDatabase() {
        assertThat(jdbc.queryForObject(
                "SELECT DATABASE()",
                String.class
        )).isEqualTo("notification_platform_test");
    }

    private static QuietHours overnightHours() {
        return new QuietHours(
                LocalTime.of(22, 0),
                LocalTime.of(7, 0),
                ZoneId.of("UTC")
        );
    }

    private static String uuidHex(UUID id) {
        return id.toString().replace("-", "");
    }
}