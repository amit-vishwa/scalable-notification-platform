package io.github.amitvishwa.notification.application.replay;

import io.github.amitvishwa.notification.application.exception.ReplayRejectedException;
import io.github.amitvishwa.notification.application.port.email.EmailProviderResult;
import io.github.amitvishwa.notification.application.processing.ClaimedNotification;
import io.github.amitvishwa.notification.application.processing.NotificationBatchProcessor;
import io.github.amitvishwa.notification.application.processing.NotificationClaimService;
import io.github.amitvishwa.notification.application.processing.NotificationOutcomeService;
import io.github.amitvishwa.notification.domain.model.ManualReplayRequest;
import io.github.amitvishwa.notification.domain.model.Notification;
import io.github.amitvishwa.notification.domain.model.NotificationKey;
import io.github.amitvishwa.notification.domain.model.NotificationReplay;
import io.github.amitvishwa.notification.domain.model.NotificationStatus;
import io.github.amitvishwa.notification.domain.preference.EmailPreference;
import io.github.amitvishwa.notification.domain.preference.EmailPreferenceSettings;
import io.github.amitvishwa.notification.domain.preference.PreferenceKey;
import io.github.amitvishwa.notification.infrastructure.persistence.DeliveryAttemptRepository;
import io.github.amitvishwa.notification.infrastructure.persistence.NotificationReplayStore;
import io.github.amitvishwa.notification.infrastructure.persistence.NotificationRepository;
import io.github.amitvishwa.notification.infrastructure.persistence.preference.EmailPreferenceRepository;
import io.github.amitvishwa.notification.operator.replay.OperatorReplayRunner;
import io.github.amitvishwa.notification.worker.NotificationWorkerScheduler;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "notification.worker.enabled=false",
                "notification.worker.poll-enabled=false",
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
@Import(NotificationReplayIntegrationTest.ReplayTestConfiguration.class)
@Isolated("Tests exercise the entire dedicated test queue")
class NotificationReplayIntegrationTest {

    private static final Instant NOW =
            Instant.parse("2026-10-17T09:00:00Z");

    private static final String UNKNOWN = "DELIVERY_OUTCOME_UNKNOWN";
    private static final String PROCESS = "replay-integration-test";
    private static final String OPERATOR = "integration-operator";
    private static final String REFERENCE = "INC-2026-001";

    @Autowired
    private NotificationReplayService replayService;

    @Autowired
    private NotificationReplayStore replayStore;

    @Autowired
    private NotificationClaimService claimService;

    @Autowired
    private NotificationOutcomeService outcomeService;

    @Autowired
    private NotificationRepository notifications;

    @Autowired
    private DeliveryAttemptRepository attempts;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private EmailPreferenceRepository preferenceRepository;

    @Autowired
    private ApplicationContext applicationContext;

    @MockitoBean(enforceOverride = true)
    private Clock clock;

    private String sourceApplication;
    private TransactionTemplate transactions;
    private ExecutorService concurrentTasks;

    @BeforeEach
    void prepareFixtures() {
        assertTestDatabase();

        assertThat(notifications.count())
                .as("Use an empty dedicated test database")
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

        sourceApplication = "replay-test-" + UUID.randomUUID();
        when(clock.instant()).thenReturn(NOW);

        preferenceRepository.saveAndFlush(EmailPreference.create(
                UUID.randomUUID(),
                new PreferenceKey(
                        sourceApplication,
                        "recipient@example.com"
                ),
                new EmailPreferenceSettings(true, "en-IN", null),
                PROCESS,
                NOW
        ));

        assertThat(AopUtils.isAopProxy(replayService)).isTrue();
        assertThat(AopUtils.isAopProxy(replayStore)).isTrue();
        assertThat(applicationContext.getBeansOfType(
                OperatorReplayRunner.class
        )).isEmpty();
        assertThat(applicationContext.getBeansOfType(
                NotificationWorkerScheduler.class
        )).isEmpty();
        assertThat(applicationContext.getBeansOfType(
                NotificationBatchProcessor.class
        )).isEmpty();
    }

    @AfterEach
    void cleanOnlyThisTestsFixtures() throws InterruptedException {
        if (concurrentTasks != null) {
            concurrentTasks.shutdownNow();

            assertThat(concurrentTasks.awaitTermination(
                    10, TimeUnit.SECONDS
            ))
                    .as("Concurrent tasks must finish before fixture cleanup")
                    .isTrue();
        }

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

    @Test
    void replaysWithAnAuditAndPreservesLifetimeHistory() {
        savePending(1);
        ClaimedNotification first = claimOne();

        assertThat(outcomeService.recordOutcome(
                first,
                EmailProviderResult.temporaryFailure("PROVIDER_UNAVAILABLE")
        )).isTrue();

        when(clock.instant()).thenReturn(NOW.plusSeconds(60));
        ClaimedNotification second = claimOne();

        assertThat(outcomeService.recordOutcome(
                second,
                EmailProviderResult.unknown(UNKNOWN)
        )).isTrue();

        Notification before = notification(id(1));
        List<Map<String, Object>> historyBefore = attemptHistory(id(1));

        assertThat(before.getLastAttemptNumber()).isEqualTo(2);
        assertThat(before.getCycleAttemptCount()).isEqualTo(2);
        assertThat(before.getRetryCount()).isEqualTo(1);

        Instant replayClock = NOW.plusSeconds(65).plusNanos(123_456_789);
        Instant replayedAt = replayClock.truncatedTo(ChronoUnit.MICROS);
        when(clock.instant()).thenReturn(replayClock);

        ManualReplayRequest request = request(before, UUID.randomUUID());
        ManualReplayResult result = replayService.replay(request);
        Notification requeued = notification(id(1));

        assertThat(result.commandId()).isEqualTo(request.commandId());
        assertThat(result.notificationId()).isEqualTo(id(1));
        assertThat(result.requeuedVersion()).isEqualTo(before.getVersion() + 1);
        assertThat(result.replayedAt()).isEqualTo(replayedAt);

        assertPayloadUnchanged(before, requeued);
        assertThat(requeued.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(requeued.getVersion()).isEqualTo(result.requeuedVersion());
        assertThat(requeued.getNextAttemptAt()).isEqualTo(replayedAt);
        assertThat(requeued.getCycleAttemptCount()).isZero();
        assertThat(requeued.getLastAttemptNumber()).isEqualTo(2);
        assertThat(requeued.getRetryCount()).isEqualTo(1);
        assertThat(requeued.getLastAttemptAt())
                .isEqualTo(before.getLastAttemptAt());
        assertThat(requeued.getFailureCode()).isNull();
        assertThat(requeued.getProviderMessageId()).isNull();
        assertThat(requeued.getProcessingExpiresAt()).isNull();
        assertThat(requeued.getUpdatedByProcess())
                .isEqualTo("notification-replay-cli");
        assertThat(requeued.getUpdatedAtTime()).isEqualTo(replayedAt);
        assertThat(attemptHistory(id(1))).isEqualTo(historyBefore);
        assertThat(replayCount()).isEqualTo(1);

        NotificationReplay audit = readAudit(request.commandId());

        assertThat(audit.matches(request)).isTrue();
        assertThat(audit.getResultingVersion()).isEqualTo(result.requeuedVersion());
        assertThat(audit.getReplayedAt()).isEqualTo(result.replayedAt());
        assertThat(audit.getCreatedByProcess())
                .isEqualTo("notification-replay-cli");
        assertThat(audit.getUpdatedByProcess())
                .isEqualTo("notification-replay-cli");
        assertThat(audit.getCreatedAtTime()).isEqualTo(replayedAt);
        assertThat(audit.getUpdatedAtTime()).isEqualTo(replayedAt);

        Map<String, Object> storedAudit = auditRow(request.commandId());

        assertThat(((Number) storedAudit.get("expected_version")).longValue())
                .isEqualTo(before.getVersion());
        assertThat(((Number) storedAudit.get("resulting_version")).longValue())
                .isEqualTo(result.requeuedVersion());
        assertThat(storedAudit.get("previous_failure_code")).isEqualTo(UNKNOWN);
        assertThat(((Number) storedAudit.get(
                "previous_cycle_attempt_count"
        )).intValue()).isEqualTo(2);
        assertThat(((Number) storedAudit.get(
                "last_attempt_number_at_replay"
        )).intValue()).isEqualTo(2);
        assertThat(storedAudit.get("operator_id")).isEqualTo(OPERATOR);
        assertThat(storedAudit.get("investigation_reference"))
                .isEqualTo(REFERENCE);

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM notification_replay
                WHERE source_application = ?
                  AND command_id = UNHEX(REPLACE(?, '-', ''))
                  AND duplicate_risk_acknowledged = TRUE
                  AND previous_sender_stopped = TRUE
                """, Long.class, sourceApplication, request.commandId().toString()
        )).isEqualTo(1L);

        ClaimedNotification replayedClaim = claimOne();

        assertThat(attempts.findById(replayedClaim.attemptId())
                .orElseThrow().getAttemptNumber()).isEqualTo(3);
        assertThat(notification(id(1)).getCycleAttemptCount()).isEqualTo(1);
        assertThat(notification(id(1)).getRetryCount()).isEqualTo(1);

        long currentVersion = notification(id(1)).getVersion();

        assertThat(outcomeService.recordOutcome(
                second,
                EmailProviderResult.accepted("stale-original-result")
        )).isFalse();

        assertThat(notification(id(1)).getVersion()).isEqualTo(currentVersion);
        assertThat(notification(id(1)).getStatus())
                .isEqualTo(NotificationStatus.PROCESSING);
        assertThat(attempts.findById(replayedClaim.attemptId())
                .orElseThrow().isCompleted()).isFalse();

        List<Map<String, Object>> currentHistory = attemptHistory(id(1));

        assertThat(currentHistory).hasSize(3);
        assertThat(currentHistory.subList(0, 2)).isEqualTo(historyBefore);
    }

    @Test
    void repeatsTheOriginalCommandAfterDeliveryWithoutRequeuingAgain() {
        failWithUnknown(1);

        ManualReplayRequest request = request(
                notification(id(1)),
                UUID.randomUUID()
        );
        ManualReplayResult original = replayService.replay(request);

        ClaimedNotification replayedClaim = claimOne();

        assertThat(outcomeService.recordOutcome(
                replayedClaim,
                EmailProviderResult.accepted("accepted-after-replay")
        )).isTrue();

        Map<String, Object> before = notificationRow(id(1));
        List<Map<String, Object>> historyBefore = attemptHistory(id(1));
        Map<String, Object> auditBefore = auditRow(request.commandId());

        when(clock.instant()).thenReturn(NOW.plusSeconds(30));

        assertThat(replayService.replay(request)).isEqualTo(original);
        assertThat(notification(id(1)).getStatus())
                .isEqualTo(NotificationStatus.SENT);
        assertThat(notificationRow(id(1))).isEqualTo(before);
        assertThat(attemptHistory(id(1))).isEqualTo(historyBefore);
        assertThat(auditRow(request.commandId())).isEqualTo(auditBefore);
        assertThat(replayCount()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"REFERENCE", "OPERATOR", "VERSION"})
    void rejectsConflictingReuseOfACommand(String changedField) {
        failWithUnknown(1);

        ManualReplayRequest original = request(
                notification(id(1)),
                UUID.randomUUID()
        );
        replayService.replay(original);

        ManualReplayRequest conflicting = new ManualReplayRequest(
                original.commandId(),
                original.notificationId(),
                original.sourceApplication(),
                "VERSION".equals(changedField)
                        ? original.expectedVersion() + 1
                        : original.expectedVersion(),
                "OPERATOR".equals(changedField) ? "other-operator" : OPERATOR,
                "REFERENCE".equals(changedField) ? "INC-2026-002" : REFERENCE,
                true,
                true
        );

        Map<String, Object> before = notificationRow(id(1));
        List<Map<String, Object>> historyBefore = attemptHistory(id(1));
        Map<String, Object> auditBefore = auditRow(original.commandId());

        assertRejected(
                conflicting,
                ReplayRejectedException.Reason.COMMAND_CONFLICT
        );

        assertThat(notificationRow(id(1))).isEqualTo(before);
        assertThat(attemptHistory(id(1))).isEqualTo(historyBefore);
        assertThat(auditRow(original.commandId())).isEqualTo(auditBefore);
        assertThat(replayCount()).isEqualTo(1);
    }

    @Test
    void rejectsAStaleVersionWithoutChangingAnything() {
        failWithUnknown(1);
        Notification failed = notification(id(1));

        ManualReplayRequest stale = new ManualReplayRequest(
                UUID.randomUUID(),
                id(1),
                sourceApplication,
                failed.getVersion() + 1,
                OPERATOR,
                REFERENCE,
                true,
                true
        );

        Map<String, Object> before = notificationRow(id(1));
        List<Map<String, Object>> historyBefore = attemptHistory(id(1));

        assertRejected(stale, ReplayRejectedException.Reason.STALE_VERSION);

        assertThat(notificationRow(id(1))).isEqualTo(before);
        assertThat(attemptHistory(id(1))).isEqualTo(historyBefore);
        assertThat(replayCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsAWrongOwnerOrMissingNotification(boolean missingId) {
        failWithUnknown(1);
        Notification failed = notification(id(1));

        ManualReplayRequest invalid = new ManualReplayRequest(
                UUID.randomUUID(),
                missingId ? UUID.randomUUID() : id(1),
                missingId
                        ? sourceApplication
                        : sourceApplication.toUpperCase(Locale.ROOT),
                failed.getVersion(),
                OPERATOR,
                REFERENCE,
                true,
                true
        );

        Map<String, Object> before = notificationRow(id(1));
        List<Map<String, Object>> historyBefore = attemptHistory(id(1));

        assertRejected(invalid, ReplayRejectedException.Reason.NOT_FOUND);

        assertThat(notificationRow(id(1))).isEqualTo(before);
        assertThat(attemptHistory(id(1))).isEqualTo(historyBefore);
        assertThat(replayCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "PROCESSING", "RETRY_PENDING", "SENT"})
    void rejectsNotificationsThatHaveNotFailed(String targetState) {
        savePending(1);

        if (!"PENDING".equals(targetState)) {
            ClaimedNotification claim = claimOne();

            if ("RETRY_PENDING".equals(targetState)) {
                assertThat(outcomeService.recordOutcome(
                        claim,
                        EmailProviderResult.temporaryFailure(
                                "PROVIDER_UNAVAILABLE"
                        )
                )).isTrue();
            } else if ("SENT".equals(targetState)) {
                assertThat(outcomeService.recordOutcome(
                        claim,
                        EmailProviderResult.accepted("already-accepted")
                )).isTrue();
            }
        }

        Notification stored = notification(id(1));
        assertThat(stored.getStatus().name()).isEqualTo(targetState);

        Map<String, Object> before = notificationRow(id(1));
        List<Map<String, Object>> historyBefore = attemptHistory(id(1));

        assertRejected(
                request(stored, UUID.randomUUID()),
                ReplayRejectedException.Reason.NOT_FAILED
        );

        assertThat(notificationRow(id(1))).isEqualTo(before);
        assertThat(attemptHistory(id(1))).isEqualTo(historyBefore);
        assertThat(replayCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"NO_OUTCOME", "NO_COMPLETION", "NEITHER"})
    void rejectsAnyOpenOrPartiallyCompletedAttempt(String shape) {
        ClaimedNotification claim = failWithUnknown(1);

        String outcome = "NO_COMPLETION".equals(shape) ? "UNKNOWN" : null;
        Timestamp completedAt = "NO_OUTCOME".equals(shape)
                ? Timestamp.from(NOW)
                : null;

        // Deliberately inconsistent fixtures verify both sides of the OR check.
        int changed = jdbc.update("""
                UPDATE delivery_attempt AS attempt
                JOIN notification AS notification_record
                  ON notification_record.notification_id =
                     attempt.notification_id
                SET attempt.outcome = ?,
                    attempt.completed_at_time = ?
                WHERE notification_record.source_application = ?
                  AND attempt.attempt_id = UNHEX(REPLACE(?, '-', ''))
                """,
                outcome,
                completedAt,
                sourceApplication,
                claim.attemptId().toString()
        );

        assertThat(changed).isEqualTo(1);

        Map<String, Object> before = notificationRow(id(1));
        List<Map<String, Object>> historyBefore = attemptHistory(id(1));

        assertRejected(
                request(notification(id(1)), UUID.randomUUID()),
                ReplayRejectedException.Reason.OPEN_ATTEMPTS
        );

        assertThat(notificationRow(id(1))).isEqualTo(before);
        assertThat(attemptHistory(id(1))).isEqualTo(historyBefore);
        assertThat(replayCount()).isZero();
    }

    @Test
    void rollsBackTheFlushedNotificationWhenAuditConstructionFails() {
        failWithUnknown(1);

        // Fault injection: the DB allows this value, but audit validation rejects it.
        int changed = jdbc.update("""
                UPDATE notification
                SET failure_code = ''
                WHERE source_application = ?
                  AND notification_id = UNHEX(REPLACE(?, '-', ''))
                """, sourceApplication, id(1).toString());

        assertThat(changed).isEqualTo(1);

        Map<String, Object> before = notificationRow(id(1));
        List<Map<String, Object>> historyBefore = attemptHistory(id(1));
        ManualReplayRequest request = request(
                notification(id(1)),
                UUID.randomUUID()
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> replayService.replay(request)
        );

        assertThat(notificationRow(id(1))).isEqualTo(before);
        assertThat(attemptHistory(id(1))).isEqualTo(historyBefore);
        assertThat(replayCount()).isZero();
    }

    @Test
    void deduplicatesConcurrentIdenticalCommandsOnTheSameNotification()
            throws Exception {
        failWithUnknown(1);
        Notification before = notification(id(1));
        List<Map<String, Object>> historyBefore = attemptHistory(id(1));
        ManualReplayRequest request = request(before, UUID.randomUUID());

        CountDownLatch start = new CountDownLatch(1);
        concurrentTasks = Executors.newFixedThreadPool(2);

        Future<ManualReplayResult> first = concurrentTasks.submit(() -> {
            awaitStart(start);
            return replayService.replay(request);
        });
        Future<ManualReplayResult> second = concurrentTasks.submit(() -> {
            awaitStart(start);
            return replayService.replay(request);
        });

        start.countDown();

        ManualReplayResult firstResult = first.get(15, TimeUnit.SECONDS);
        ManualReplayResult secondResult = second.get(15, TimeUnit.SECONDS);

        assertThat(secondResult).isEqualTo(firstResult);
        assertThat(firstResult.requeuedVersion())
                .isEqualTo(before.getVersion() + 1);
        assertThat(notification(id(1)).getVersion())
                .isEqualTo(firstResult.requeuedVersion());
        assertThat(notification(id(1)).getStatus())
                .isEqualTo(NotificationStatus.PENDING);
        assertThat(notification(id(1)).getCycleAttemptCount()).isZero();
        assertThat(attemptHistory(id(1))).isEqualTo(historyBefore);
        assertThat(replayCount()).isEqualTo(1);
    }

    @Test
    void permitsOnlyOneOfTwoDifferentCommandsForTheSameFailedVersion()
            throws Exception {
        failWithUnknown(1);
        Notification before = notification(id(1));
        List<Map<String, Object>> historyBefore = attemptHistory(id(1));

        ManualReplayRequest firstRequest = request(before, UUID.randomUUID());
        ManualReplayRequest secondRequest = request(before, UUID.randomUUID());

        CountDownLatch start = new CountDownLatch(1);
        concurrentTasks = Executors.newFixedThreadPool(2);

        Future<ReplayInvocation> first = concurrentTasks.submit(() -> {
            awaitStart(start);
            return invokeReplay(firstRequest);
        });
        Future<ReplayInvocation> second = concurrentTasks.submit(() -> {
            awaitStart(start);
            return invokeReplay(secondRequest);
        });

        start.countDown();

        List<ReplayInvocation> outcomes = List.of(
                first.get(15, TimeUnit.SECONDS),
                second.get(15, TimeUnit.SECONDS)
        );
        List<ReplayInvocation> successes = outcomes.stream()
                .filter(invocation -> invocation.failure() == null)
                .toList();
        List<ReplayInvocation> failures = outcomes.stream()
                .filter(invocation -> invocation.failure() != null)
                .toList();

        assertThat(successes).as("Replay outcomes: %s", outcomes).hasSize(1);
        assertThat(failures).hasSize(1);
        assertThat(failures.get(0).failure())
                .isInstanceOf(ReplayRejectedException.class);
        assertThat(((ReplayRejectedException) failures.get(0).failure()).reason())
                .isEqualTo(ReplayRejectedException.Reason.NOT_FAILED);

        assertThat(notification(id(1)).getVersion())
                .isEqualTo(before.getVersion() + 1);
        assertThat(notification(id(1)).getStatus())
                .isEqualTo(NotificationStatus.PENDING);
        assertThat(attemptHistory(id(1))).isEqualTo(historyBefore);
        assertThat(replayCount()).isEqualTo(1);
    }

    @Test
    void rollsBackTheLosingNotificationWhenConcurrentAuditIdsCollide()
            throws Exception {
        // Prepare both failures before either notification can be requeued.
        failWithUnknown(1);
        failWithUnknown(2);

        Notification firstNotification = notification(id(1));
        Notification secondNotification = notification(id(2));
        Map<UUID, Map<String, Object>> before = Map.of(
                id(1), notificationRow(id(1)),
                id(2), notificationRow(id(2))
        );
        List<Map<String, Object>> firstHistory = attemptHistory(id(1));
        List<Map<String, Object>> secondHistory = attemptHistory(id(2));

        UUID sharedCommandId = UUID.randomUUID();
        ManualReplayRequest firstRequest =
                request(firstNotification, sharedCommandId);
        ManualReplayRequest secondRequest =
                request(secondNotification, sharedCommandId);

        // Clock is read after the absent-audit check while each parent is locked.
        // The barrier forces both transactions past that check before insertion.
        CyclicBarrier bothChecked = new CyclicBarrier(2);
        when(clock.instant()).thenAnswer(invocation -> {
            awaitBarrier(bothChecked);
            return NOW;
        });

        concurrentTasks = Executors.newFixedThreadPool(2);
        Future<ReplayInvocation> first = concurrentTasks.submit(
                () -> invokeReplay(firstRequest)
        );
        Future<ReplayInvocation> second = concurrentTasks.submit(
                () -> invokeReplay(secondRequest)
        );

        List<ReplayInvocation> outcomes;
        try {
            outcomes = List.of(
                    first.get(20, TimeUnit.SECONDS),
                    second.get(20, TimeUnit.SECONDS)
            );
        } finally {
            // Ordinary when(clock.instant()) would execute the blocking stub.
            doReturn(NOW).when(clock).instant();
        }

        List<ReplayInvocation> successes = outcomes.stream()
                .filter(invocation -> invocation.failure() == null)
                .toList();
        List<ReplayInvocation> failures = outcomes.stream()
                .filter(invocation -> invocation.failure() != null)
                .toList();

        assertThat(successes).as("Replay outcomes: %s", outcomes).hasSize(1);
        assertThat(failures).hasSize(1);
        assertThat(failures.get(0).failure())
                .isInstanceOf(DataIntegrityViolationException.class);

        ManualReplayResult winner = successes.get(0).result();
        UUID losingId = winner.notificationId().equals(id(1)) ? id(2) : id(1);
        Notification winningNotification = notification(winner.notificationId());

        assertThat(winningNotification.getStatus())
                .isEqualTo(NotificationStatus.PENDING);
        assertThat(winningNotification.getVersion())
                .isEqualTo(winner.requeuedVersion());
        assertThat(winningNotification.getCycleAttemptCount()).isZero();

        // The losing transaction had already flushed its notification update.
        assertThat(notificationRow(losingId)).isEqualTo(before.get(losingId));
        assertThat(notification(losingId).getStatus())
                .isEqualTo(NotificationStatus.FAILED);
        assertThat(attemptHistory(id(1))).isEqualTo(firstHistory);
        assertThat(attemptHistory(id(2))).isEqualTo(secondHistory);
        assertThat(replayCount()).isEqualTo(1);

        NotificationReplay storedAudit = readAudit(sharedCommandId);

        assertThat(storedAudit.getNotificationId())
                .isEqualTo(winner.notificationId());
        assertThat(storedAudit.getResultingVersion())
                .isEqualTo(winner.requeuedVersion());
    }

    private void savePending(long value) {
        notifications.saveAndFlush(Notification.create(
                id(value),
                new NotificationKey(sourceApplication, "key-" + value),
                "a".repeat(64),
                "recipient@example.com",
                "Replay integration test",
                "Replay integration test body",
                PROCESS,
                NOW.minusSeconds(60)
        ));
    }

    private ClaimedNotification failWithUnknown(long value) {
        savePending(value);
        ClaimedNotification claim = claimOne();

        assertThat(claim.notificationId()).isEqualTo(id(value));
        assertThat(outcomeService.recordOutcome(
                claim,
                EmailProviderResult.unknown(UNKNOWN)
        )).isTrue();
        assertThat(notification(id(value)).getStatus())
                .isEqualTo(NotificationStatus.FAILED);

        return claim;
    }

    private ClaimedNotification claimOne() {
        List<ClaimedNotification> claims = claimService.claimEligible(1);
        assertThat(claims).hasSize(1);
        return claims.get(0);
    }

    private Notification notification(UUID notificationId) {
        return notifications.findById(notificationId).orElseThrow();
    }

    private ManualReplayRequest request(
            Notification notification,
            UUID commandId
    ) {
        return new ManualReplayRequest(
                commandId,
                notification.getNotificationId(),
                sourceApplication,
                notification.getVersion(),
                OPERATOR,
                REFERENCE,
                true,
                true
        );
    }

    private void assertRejected(
            ManualReplayRequest request,
            ReplayRejectedException.Reason reason
    ) {
        ReplayRejectedException exception = assertThrows(
                ReplayRejectedException.class,
                () -> replayService.replay(request)
        );
        assertThat(exception.reason()).isEqualTo(reason);
    }

    private void assertPayloadUnchanged(Notification before, Notification after) {
        assertThat(after.getNotificationId())
                .isEqualTo(before.getNotificationId());
        assertThat(after.getNotificationKey())
                .isEqualTo(before.getNotificationKey());
        assertThat(after.getRequestBodyHash())
                .isEqualTo(before.getRequestBodyHash());
        assertThat(after.getRecipientEmail())
                .isEqualTo(before.getRecipientEmail());
        assertThat(after.getSubject()).isEqualTo(before.getSubject());
        assertThat(after.getBody()).isEqualTo(before.getBody());
        assertThat(after.getCreatedByProcess())
                .isEqualTo(before.getCreatedByProcess());
        assertThat(after.getCreatedAtTime())
                .isEqualTo(before.getCreatedAtTime());
    }

    private NotificationReplay readAudit(UUID commandId) {
        return Objects.requireNonNull(transactions.execute(transaction ->
                replayStore.findByCommandId(commandId).orElseThrow()
        ));
    }

    private long replayCount() {
        return Objects.requireNonNull(jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM notification_replay
                WHERE source_application = ?
                """, Long.class, sourceApplication));
    }

    private Map<String, Object> notificationRow(UUID notificationId) {
        return jdbc.queryForMap("""
                SELECT HEX(notification_id) AS notification_id,
                       source_application, idempotency_key, request_body_hash,
                       recipient_email, subject, body, status, retry_count,
                       next_attempt_at, last_attempt_at, last_attempt_number,
                       cycle_attempt_count, processing_expires_at, failure_code,
                       provider_message_id, version, created_by_process,
                       updated_by_process, created_at_time, updated_at_time
                FROM notification
                WHERE source_application = ?
                  AND notification_id = UNHEX(REPLACE(?, '-', ''))
                """, sourceApplication, notificationId.toString());
    }

    private List<Map<String, Object>> attemptHistory(UUID notificationId) {
        return jdbc.queryForList("""
                SELECT HEX(attempt.attempt_id) AS attempt_id,
                       HEX(attempt.notification_id) AS notification_id,
                       attempt.attempt_number, attempt.started_at_time,
                       attempt.completed_at_time, attempt.outcome,
                       attempt.failure_code, attempt.provider_message_id,
                       attempt.created_by_process, attempt.updated_by_process,
                       attempt.created_at_time, attempt.updated_at_time
                FROM delivery_attempt AS attempt
                JOIN notification AS notification_record
                  ON notification_record.notification_id = attempt.notification_id
                WHERE notification_record.source_application = ?
                  AND attempt.notification_id = UNHEX(REPLACE(?, '-', ''))
                ORDER BY attempt.attempt_number
                """, sourceApplication, notificationId.toString());
    }

    private Map<String, Object> auditRow(UUID commandId) {
        return jdbc.queryForMap("""
                SELECT HEX(command_id) AS command_id,
                       HEX(notification_id) AS notification_id,
                       source_application, expected_version, resulting_version,
                       previous_failure_code, previous_cycle_attempt_count,
                       last_attempt_number_at_replay, operator_id,
                       investigation_reference, duplicate_risk_acknowledged,
                       previous_sender_stopped, created_by_process,
                       updated_by_process, created_at_time, updated_at_time
                FROM notification_replay
                WHERE source_application = ?
                  AND command_id = UNHEX(REPLACE(?, '-', ''))
                """, sourceApplication, commandId.toString());
    }

    private ReplayInvocation invokeReplay(ManualReplayRequest request) {
        try {
            return new ReplayInvocation(replayService.replay(request), null);
        } catch (RuntimeException exception) {
            return new ReplayInvocation(null, exception);
        }
    }

    private void assertTestDatabase() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class))
                .as("Refusing fixture mutations outside the test database")
                .isEqualTo("notification_platform_test");
    }

    private static UUID id(long value) {
        return new UUID(0L, value);
    }

    private static void awaitStart(CountDownLatch start) {
        try {
            if (!start.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out starting replay test");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Replay test was interrupted",
                    exception
            );
        }
    }

    private static void awaitBarrier(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Replay barrier was interrupted",
                    exception
            );
        } catch (BrokenBarrierException | TimeoutException exception) {
            throw new IllegalStateException(
                    "Replay concurrency barrier failed",
                    exception
            );
        }
    }

    private record ReplayInvocation(
            ManualReplayResult result,
            RuntimeException failure
    ) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ReplayTestConfiguration {

        @Bean
        NotificationReplayStore replayStore(
                EntityManagerFactory entityManagerFactory
        ) {
            return new NotificationReplayStore(entityManagerFactory);
        }

        @Bean
        NotificationReplayService replayService(
                NotificationRepository notifications,
                DeliveryAttemptRepository attempts,
                NotificationReplayStore replayStore,
                Clock clock
        ) {
            return new NotificationReplayService(
                    notifications,
                    attempts,
                    replayStore,
                    clock
            );
        }
    }
}
