package io.github.amitvishwa.notification.application.processing;

import io.github.amitvishwa.notification.application.port.email.EmailMessage;
import io.github.amitvishwa.notification.application.port.email.EmailProvider;
import io.github.amitvishwa.notification.application.port.email.EmailProviderResult;
import io.github.amitvishwa.notification.domain.model.DeliveryAttempt;
import io.github.amitvishwa.notification.domain.model.DeliveryAttemptOutcome;
import io.github.amitvishwa.notification.domain.model.Notification;
import io.github.amitvishwa.notification.domain.model.NotificationKey;
import io.github.amitvishwa.notification.domain.model.NotificationStatus;
import io.github.amitvishwa.notification.infrastructure.persistence.DeliveryAttemptRepository;
import io.github.amitvishwa.notification.infrastructure.persistence.NotificationRepository;
import io.github.amitvishwa.notification.worker.NotificationWorkerScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "notification.retry.max-attempts=5",
        "notification.retry.initial-delay=PT1M",
        "notification.retry.max-delay=PT15M",
        "notification.retry.processing-lease=PT5M",
        "notification.worker.enabled=true",
        "notification.worker.poll-enabled=false",
        "spring.datasource.url=jdbc:mysql://localhost:3306/"
                + "notification_platform_test?serverTimezone=UTC",
        "spring.datasource.username=notification_test",
        "spring.flyway.clean-disabled=true"
})
@ActiveProfiles("test")
@Isolated("Tests exercise the entire dedicated test queue")
class NotificationWorkerIntegrationTest {

    private static final Instant NOW =
            Instant.parse("2026-10-04T09:00:00Z");

    private static final String TEST_PROCESS = "worker-integration-test";
    private static final String WORKER_PROCESS = "notification-worker";
    private static final String REQUEST_HASH = "a".repeat(64);

    @Autowired
    private NotificationClaimService claimService;

    @Autowired
    private NotificationOutcomeService outcomeService;

    @Autowired
    private NotificationRecoveryService recoveryService;

    @Autowired
    private NotificationBatchProcessor processor;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private DeliveryAttemptRepository deliveryAttemptRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ApplicationContext applicationContext;

    @MockitoBean(enforceOverride = true)
    private EmailProvider emailProvider;

    @MockitoBean(enforceOverride = true)
    private Clock clock;

    private String sourceApplication;
    private TransactionTemplate transactions;
    private ExecutorService concurrentTasks;

    @BeforeEach
    void prepareFixtures() {
        assertTestDatabase();

        assertThat(notificationRepository.count())
                .as("Use an empty dedicated test database")
                .isZero();
        assertThat(deliveryAttemptRepository.count()).isZero();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification_replay",
                Long.class
        )).isZero();

        sourceApplication = "worker-test-" + UUID.randomUUID();

        transactions = new TransactionTemplate(transactionManager);
        transactions.setIsolationLevel(
                TransactionDefinition.ISOLATION_READ_COMMITTED
        );

        when(clock.instant()).thenReturn(NOW);

        assertThat(applicationContext.getBeansOfType(
                NotificationWorkerScheduler.class
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
            jdbcTemplate.update("""
                    DELETE FROM notification_replay
                    WHERE notification_id IN (
                        SELECT notification_id
                        FROM notification
                        WHERE source_application = ?
                    )
                    """, sourceApplication);

            jdbcTemplate.update("""
                    DELETE FROM delivery_attempt
                    WHERE notification_id IN (
                        SELECT notification_id
                        FROM notification
                        WHERE source_application = ?
                    )
                    """, sourceApplication);

            jdbcTemplate.update("""
                    DELETE FROM notification
                    WHERE source_application = ?
                    """, sourceApplication);
        });
    }

    @Test
    void commitsProcessingStateAndIncompleteAttemptBeforeReturningClaim() {
        Notification original = notificationRepository.saveAndFlush(
                pending(1, NOW.minusSeconds(30))
        );

        List<ClaimedNotification> claims = claimService.claimEligible(1);

        assertThat(claims).hasSize(1);
        ClaimedNotification claim = claims.get(0);

        assertThat(claim.notificationId())
                .isEqualTo(original.getNotificationId());
        assertThat(claim.message()).isEqualTo(new EmailMessage(
                original.getNotificationId(),
                original.getRecipientEmail(),
                original.getSubject(),
                original.getBody()
        ));

        Notification stored = notification(claim.notificationId());

        assertThat(stored.getStatus())
                .isEqualTo(NotificationStatus.PROCESSING);
        assertThat(stored.getLastAttemptAt()).isEqualTo(NOW);
        assertThat(stored.getLastAttemptNumber()).isEqualTo(1);
        assertThat(stored.getCycleAttemptCount()).isEqualTo(1);
        assertThat(stored.getProcessingExpiresAt())
                .isEqualTo(NOW.plusSeconds(300));
        assertThat(stored.getUpdatedByProcess())
                .isEqualTo(WORKER_PROCESS);
        assertThat(stored.getUpdatedAtTime()).isEqualTo(NOW);
        assertThat(stored.getVersion())
                .isGreaterThan(original.getVersion());

        DeliveryAttempt attempt = attempt(claim.attemptId());

        assertThat(attempt.getAttemptNumber()).isEqualTo(1);
        assertThat(attempt.getStartedAtTime()).isEqualTo(NOW);
        assertThat(attempt.getOutcome()).isNull();
        assertThat(attempt.getCompletedAtTime()).isNull();
        assertThat(attempt.getCreatedByProcess())
                .isEqualTo(WORKER_PROCESS);

        assertThat(claimService.claimEligible(1)).isEmpty();
        assertThat(deliveryAttemptRepository.count()).isEqualTo(1);
    }

    @Test
    void ordersDuePendingNotificationsAndRespectsBatchLimit() {
        notificationRepository.saveAllAndFlush(List.of(
                pending(6, NOW.minusSeconds(300)),
                pending(7, NOW.minusSeconds(300)),
                pending(8, NOW.minusSeconds(300)),
                pending(9, NOW.minusSeconds(300))
        ));

        List<ClaimedNotification> history = claimService.claimEligible(4);

        assertThat(history)
                .extracting(ClaimedNotification::notificationId)
                .containsExactly(id(6), id(7), id(8), id(9));

        assertThat(outcomeService.recordOutcome(
                history.get(0),
                EmailProviderResult.temporaryFailure("TEMPORARY_ERROR")
        )).isTrue();

        // Notification 7 remains PROCESSING with a live lease.

        assertThat(outcomeService.recordOutcome(
                history.get(2),
                EmailProviderResult.accepted("already-accepted")
        )).isTrue();

        assertThat(outcomeService.recordOutcome(
                history.get(3),
                EmailProviderResult.permanentFailure("INVALID_RECIPIENT")
        )).isTrue();

        Notification earliest = pending(4, NOW.minusSeconds(300));
        Notification olderSameDue = pending(3, NOW.minusSeconds(200));
        Notification firstTie = pending(1, NOW.minusSeconds(100));
        Notification secondTie = pending(2, NOW.minusSeconds(100));
        Notification future = pending(5, NOW.plusSeconds(60));

        notificationRepository.saveAllAndFlush(List.of(
                secondTie,
                future,
                olderSameDue,
                firstTie,
                earliest
        ));

        setDueTime(earliest, NOW.minusSeconds(100));
        setDueTime(olderSameDue, NOW.minusSeconds(50));
        setDueTime(firstTie, NOW.minusSeconds(50));
        setDueTime(secondTie, NOW.minusSeconds(50));

        assertThat(claimService.claimEligible(3))
                .extracting(ClaimedNotification::notificationId)
                .containsExactly(id(4), id(3), id(1));

        assertThat(notification(id(2)).getStatus())
                .isEqualTo(NotificationStatus.PENDING);

        assertThat(claimService.claimEligible(10))
                .extracting(ClaimedNotification::notificationId)
                .containsExactly(id(2));

        assertThat(claimService.claimEligible(10)).isEmpty();

        // Four historical claims plus four newly claimed notifications.
        assertThat(deliveryAttemptRepository.count()).isEqualTo(8);

        assertThat(notification(id(5)).getStatus())
                .isEqualTo(NotificationStatus.PENDING);
        assertThat(notification(id(6)).getStatus())
                .isEqualTo(NotificationStatus.RETRY_PENDING);
        assertThat(notification(id(7)).getStatus())
                .isEqualTo(NotificationStatus.PROCESSING);
        assertThat(notification(id(8)).getStatus())
                .isEqualTo(NotificationStatus.SENT);
        assertThat(notification(id(9)).getStatus())
                .isEqualTo(NotificationStatus.FAILED);
    }

    @Test
    void rejectsInvalidBatchSizesWithoutCreatingAttempts() {
        notificationRepository.saveAndFlush(
                pending(1, NOW.minusSeconds(30))
        );

        for (int invalidSize : new int[]{-1, 0, 11}) {
            assertThatThrownBy(
                    () -> claimService.claimEligible(invalidSize)
            ).isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(
                    () -> recoveryService.recoverExpired(invalidSize)
            ).isInstanceOf(IllegalArgumentException.class);
        }

        assertThat(notification(id(1)).getStatus())
                .isEqualTo(NotificationStatus.PENDING);
        assertThat(deliveryAttemptRepository.count()).isZero();
    }

    @Test
    void skipsLockedNotificationWithoutWaitingForLockRelease()
            throws Exception {
        notificationRepository.saveAllAndFlush(List.of(
                pending(1, NOW.minusSeconds(60)),
                pending(2, NOW.minusSeconds(30))
        ));

        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        concurrentTasks = Executors.newFixedThreadPool(2);

        Future<?> holder = concurrentTasks.submit(() ->
                transactions.executeWithoutResult(transaction -> {
                    Notification held = notificationRepository
                            .lockById(id(1))
                            .orElseThrow();

                    assertThat(held.getNotificationId())
                            .isEqualTo(id(1));

                    locked.countDown();
                    awaitRelease(release);
                })
        );

        try {
            assertThat(locked.await(10, TimeUnit.SECONDS))
                    .as("First transaction must acquire its lock")
                    .isTrue();

            Future<List<ClaimedNotification>> claimant =
                    concurrentTasks.submit(
                            () -> claimService.claimEligible(2)
                    );

            assertThat(claimant.get(10, TimeUnit.SECONDS))
                    .extracting(ClaimedNotification::notificationId)
                    .containsExactly(id(2));

            // The first transaction is still holding its lock.
            assertThat(release.getCount()).isEqualTo(1L);
        } finally {
            release.countDown();
        }

        holder.get(10, TimeUnit.SECONDS);

        assertThat(claimService.claimEligible(2))
                .extracting(ClaimedNotification::notificationId)
                .containsExactly(id(1));

        assertThat(claimService.claimEligible(2)).isEmpty();
        assertThat(deliveryAttemptRepository.count()).isEqualTo(2);
    }

    @Test
    void rollsBackEntireClaimBatchWhenAttemptInsertFails() {
        List<Notification> saved =
                notificationRepository.saveAllAndFlush(List.of(
                        pending(1, NOW.minusSeconds(60)),
                        pending(2, NOW.minusSeconds(30))
                ));

        // Deliberately inconsistent fixture to trigger the unique constraint.
        DeliveryAttempt existing =
                deliveryAttemptRepository.saveAndFlush(
                        DeliveryAttempt.start(
                                UUID.randomUUID(),
                                saved.get(1),
                                1,
                                TEST_PROCESS,
                                NOW.minusSeconds(5)
                        )
                );

        assertThatThrownBy(() -> claimService.claimEligible(2))
                .isInstanceOf(DataIntegrityViolationException.class);

        for (Notification original : saved) {
            Notification stored =
                    notification(original.getNotificationId());

            assertThat(stored.getStatus())
                    .isEqualTo(NotificationStatus.PENDING);
            assertThat(stored.getLastAttemptAt()).isNull();
            assertThat(stored.getVersion())
                    .isEqualTo(original.getVersion());
            assertThat(stored.getUpdatedByProcess())
                    .isEqualTo(TEST_PROCESS);
            assertThat(stored.getLastAttemptNumber()).isZero();
            assertThat(stored.getCycleAttemptCount()).isZero();
            assertThat(stored.getProcessingExpiresAt()).isNull();
        }

        assertThat(deliveryAttemptRepository.count()).isEqualTo(1);
        assertThat(attempt(existing.getAttemptId()).isCompleted()).isFalse();
    }

    @ParameterizedTest
    @MethodSource("providerOutcomes")
    void persistsNotificationAndAttemptOutcomeTogether(
            EmailProviderResult result,
            NotificationStatus expectedStatus,
            int expectedRetryCount
    ) {
        notificationRepository.saveAndFlush(
                pending(1, NOW.minusSeconds(30))
        );

        ClaimedNotification claim = claimService.claimEligible(1).get(0);

        assertThat(outcomeService.recordOutcome(claim, result)).isTrue();

        Notification stored = notification(claim.notificationId());
        DeliveryAttempt completedAttempt = attempt(claim.attemptId());

        assertThat(stored.getStatus()).isEqualTo(expectedStatus);
        assertThat(stored.getRetryCount()).isEqualTo(expectedRetryCount);
        assertThat(stored.getFailureCode()).isEqualTo(result.failureCode());
        assertThat(stored.getProviderMessageId())
                .isEqualTo(result.providerMessageId());
        assertThat(stored.getProcessingExpiresAt()).isNull();
        assertThat(stored.getLastAttemptNumber()).isEqualTo(1);
        assertThat(stored.getCycleAttemptCount()).isEqualTo(1);
        assertThat(stored.getUpdatedByProcess()).isEqualTo(WORKER_PROCESS);
        assertThat(stored.getUpdatedAtTime()).isEqualTo(NOW);

        assertThat(completedAttempt.getOutcome()).isEqualTo(result.outcome());
        assertThat(completedAttempt.getCompletedAtTime()).isEqualTo(NOW);
        assertThat(completedAttempt.getFailureCode())
                .isEqualTo(result.failureCode());
        assertThat(completedAttempt.getProviderMessageId())
                .isEqualTo(result.providerMessageId());
        assertThat(completedAttempt.getUpdatedByProcess())
                .isEqualTo(WORKER_PROCESS);

        long completedVersion = stored.getVersion();

        assertThat(outcomeService.recordOutcome(claim, result)).isFalse();
        assertThat(notification(claim.notificationId()).getVersion())
                .isEqualTo(completedVersion);

        if (expectedStatus == NotificationStatus.RETRY_PENDING) {
            assertThat(stored.getNextAttemptAt())
                    .isEqualTo(NOW.plusSeconds(60));

            when(clock.instant()).thenReturn(NOW.plusSeconds(59));
            assertThat(claimService.claimEligible(10)).isEmpty();

            when(clock.instant()).thenReturn(NOW.plusSeconds(60));

            List<ClaimedNotification> retry = claimService.claimEligible(1);
            assertThat(retry).hasSize(1);

            assertThat(attempt(retry.get(0).attemptId()).getAttemptNumber())
                    .isEqualTo(2);
            assertThat(notification(claim.notificationId()).getCycleAttemptCount())
                    .isEqualTo(2);

            // The original result must not overwrite the newer attempt.
            assertThat(outcomeService.recordOutcome(
                    claim,
                    EmailProviderResult.accepted("late-original-result")
            )).isFalse();

            assertThat(notification(claim.notificationId()).getStatus())
                    .isEqualTo(NotificationStatus.PROCESSING);
            assertThat(attempt(retry.get(0).attemptId()).isCompleted()).isFalse();

            DeliveryAttempt originalAttempt = attempt(claim.attemptId());

            assertThat(originalAttempt.getOutcome())
                    .isEqualTo(DeliveryAttemptOutcome.TEMPORARY_FAILURE);
            assertThat(originalAttempt.getCompletedAtTime()).isEqualTo(NOW);
            assertThat(originalAttempt.getFailureCode())
                    .isEqualTo(result.failureCode());
            assertThat(originalAttempt.getProviderMessageId()).isNull();

            assertThat(deliveryAttemptRepository.count()).isEqualTo(2);
        } else {
            assertThat(deliveryAttemptRepository.count()).isEqualTo(1);
        }
    }

    @Test
    void rejectsInvalidCompletionWithoutPersistingChanges() {
        notificationRepository.saveAndFlush(
                pending(1, NOW.minusSeconds(30))
        );

        ClaimedNotification claim = claimService.claimEligible(1).get(0);

        // Completion cannot precede the recorded attempt start.
        when(clock.instant()).thenReturn(NOW.minusSeconds(1));

        assertThatThrownBy(() -> outcomeService.recordOutcome(
                claim,
                EmailProviderResult.accepted("provider-message-123")
        )).isInstanceOf(IllegalArgumentException.class);

        assertThat(notification(claim.notificationId()).getStatus())
                .isEqualTo(NotificationStatus.PROCESSING);

        DeliveryAttempt stored = attempt(claim.attemptId());

        assertThat(stored.getOutcome()).isNull();
        assertThat(stored.getCompletedAtTime()).isNull();
        assertThat(stored.getProviderMessageId()).isNull();
        assertThat(stored.getFailureCode()).isNull();
    }

    @Test
    void invokesProviderOutsideTransactionAfterClaimHasCommitted() {
        notificationRepository.saveAndFlush(
                pending(1, NOW.minusSeconds(30))
        );

        when(emailProvider.send(any(EmailMessage.class)))
                .thenAnswer(invocation -> {
                    assertThat(TransactionSynchronizationManager
                            .isActualTransactionActive()).isFalse();

                    assertThat(jdbcTemplate.queryForObject("""
                            SELECT status
                            FROM notification
                            WHERE source_application = ?
                              AND idempotency_key = ?
                            """,
                            String.class,
                            sourceApplication,
                            "key-1"
                    )).isEqualTo("PROCESSING");

                    assertThat(jdbcTemplate.queryForObject("""
                            SELECT COUNT(*)
                            FROM delivery_attempt
                            WHERE outcome IS NULL
                              AND completed_at_time IS NULL
                            """, Long.class)).isEqualTo(1L);

                    return EmailProviderResult.accepted(
                            "provider-message-123"
                    );
                });

        transactions.executeWithoutResult(transaction -> {
            assertThat(TransactionSynchronizationManager
                    .isActualTransactionActive()).isTrue();

            processor.processBatch(1);

            assertThat(TransactionSynchronizationManager
                    .isActualTransactionActive()).isTrue();
        });

        verify(emailProvider).send(any(EmailMessage.class));

        assertThat(notification(id(1)).getStatus())
                .isEqualTo(NotificationStatus.SENT);
        assertThat(deliveryAttemptRepository.count()).isEqualTo(1);
    }

    @Test
    void exhaustsAfterFiveTotalAttemptsWithoutResending() {
        notificationRepository.saveAndFlush(
                pending(1, NOW.minusSeconds(30))
        );

        List<UUID> attemptIds = new ArrayList<>();
        Instant attemptAt = NOW;

        for (int number = 1; number <= 5; number++) {
            when(clock.instant()).thenReturn(attemptAt);

            List<ClaimedNotification> claims =
                    claimService.claimEligible(1);

            assertThat(claims).hasSize(1);

            ClaimedNotification claim = claims.get(0);
            attemptIds.add(claim.attemptId());

            assertThat(attempt(claim.attemptId()).getAttemptNumber())
                    .isEqualTo(number);

            assertThat(outcomeService.recordOutcome(
                    claim,
                    EmailProviderResult.temporaryFailure(
                            "PROVIDER_UNAVAILABLE"
                    )
            )).isTrue();

            Notification stored = notification(id(1));

            assertThat(stored.getLastAttemptNumber()).isEqualTo(number);
            assertThat(stored.getCycleAttemptCount()).isEqualTo(number);
            assertThat(stored.getProcessingExpiresAt()).isNull();

            if (number < 5) {
                long delaySeconds = 60L << (number - 1);
                Instant dueAt = attemptAt.plusSeconds(delaySeconds);

                assertThat(stored.getStatus())
                        .isEqualTo(NotificationStatus.RETRY_PENDING);
                assertThat(stored.getRetryCount()).isEqualTo(number);
                assertThat(stored.getNextAttemptAt()).isEqualTo(dueAt);

                when(clock.instant()).thenReturn(dueAt.minusSeconds(1));
                assertThat(claimService.claimEligible(1)).isEmpty();

                attemptAt = dueAt;
            } else {
                assertThat(stored.getStatus())
                        .isEqualTo(NotificationStatus.FAILED);
                assertThat(stored.getFailureCode())
                        .isEqualTo(ProcessingFailureCodes.RETRY_EXHAUSTED);
                assertThat(stored.getRetryCount()).isEqualTo(4);
            }
        }

        for (UUID attemptId : attemptIds) {
            DeliveryAttempt completed = attempt(attemptId);

            assertThat(completed.getOutcome())
                    .isEqualTo(DeliveryAttemptOutcome.TEMPORARY_FAILURE);
            assertThat(completed.getFailureCode())
                    .isEqualTo("PROVIDER_UNAVAILABLE");
            assertThat(completed.getCompletedAtTime()).isNotNull();
        }

        long failedVersion = notification(id(1)).getVersion();

        when(clock.instant()).thenReturn(attemptAt.plusSeconds(86_400));
        processor.processBatch(1);

        verifyNoInteractions(emailProvider);

        assertThat(deliveryAttemptRepository.count()).isEqualTo(5);
        assertThat(notification(id(1)).getVersion())
                .isEqualTo(failedVersion);
    }

    @Test
    void ordersDueRetriesTogetherWithPendingWork() {
        Notification first = pending(1, NOW.minusSeconds(300));
        Notification future = pending(2, NOW.minusSeconds(300));
        Notification laterCreated = pending(3, NOW.minusSeconds(200));

        notificationRepository.saveAllAndFlush(
                List.of(first, future, laterCreated)
        );

        ClaimedNotification original =
                claimService.claimEligible(1).get(0);

        assertThat(original.notificationId()).isEqualTo(id(1));

        assertThat(outcomeService.recordOutcome(
                original,
                EmailProviderResult.temporaryFailure("PROVIDER_UNAVAILABLE")
        )).isTrue();

        setDueTime(future, NOW.plusSeconds(61));
        setDueTime(laterCreated, NOW.plusSeconds(60));

        when(clock.instant()).thenReturn(NOW.plusSeconds(60));

        List<ClaimedNotification> due = claimService.claimEligible(2);

        assertThat(due)
                .extracting(ClaimedNotification::notificationId)
                .containsExactly(id(1), id(3));

        assertThat(attempt(due.get(0).attemptId()).getAttemptNumber())
                .isEqualTo(2);
        assertThat(attempt(due.get(1).attemptId()).getAttemptNumber())
                .isEqualTo(1);

        assertThat(claimService.claimEligible(10)).isEmpty();

        when(clock.instant()).thenReturn(NOW.plusSeconds(61));

        assertThat(claimService.claimEligible(1))
                .extracting(ClaimedNotification::notificationId)
                .containsExactly(id(2));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void quarantinesAtTheExactLeaseBoundary(boolean checkBeforeSending) {
        notificationRepository.saveAndFlush(
                pending(1, NOW.minusSeconds(30))
        );

        ClaimedNotification claim =
                claimService.claimEligible(1).get(0);

        when(clock.instant()).thenReturn(NOW.plusSeconds(299));
        assertThat(outcomeService.canSend(claim)).isTrue();

        long processingVersion =
                notification(claim.notificationId()).getVersion();

        Instant expiredAt = NOW.plusSeconds(300);
        when(clock.instant()).thenReturn(expiredAt);

        if (checkBeforeSending) {
            assertThat(outcomeService.canSend(claim)).isFalse();
        } else {
            assertThat(outcomeService.recordOutcome(
                    claim,
                    EmailProviderResult.accepted("late-provider-result")
            )).isFalse();
        }

        Notification failed = assertQuarantined(
                claim,
                WORKER_PROCESS,
                expiredAt
        );

        assertThat(failed.getVersion()).isEqualTo(processingVersion + 1);

        long failedVersion = failed.getVersion();

        assertThat(outcomeService.recordOutcome(
                claim,
                EmailProviderResult.accepted("another-late-result")
        )).isFalse();

        assertThat(recoveryService.recoverExpired(10)).isZero();
        assertThat(claimService.claimEligible(10)).isEmpty();

        assertThat(notification(claim.notificationId()).getVersion())
                .isEqualTo(failedVersion);

        verifyNoInteractions(emailProvider);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void recoversOnlyExpiredWorkIncludingLegacyRows(boolean legacyLease) {
        notificationRepository.saveAndFlush(
                pending(1, NOW.minusSeconds(30))
        );

        ClaimedNotification claim =
                claimService.claimEligible(1).get(0);

        if (legacyLease) {
            // Simulate a historical PROCESSING row without a stored lease.
            int updated = jdbcTemplate.update("""
                UPDATE notification
                SET processing_expires_at = NULL
                WHERE source_application = ?
                  AND idempotency_key = ?
                """, sourceApplication, "key-1");

            assertThat(updated).isEqualTo(1);
        }

        long processingVersion =
                notification(claim.notificationId()).getVersion();

        when(clock.instant()).thenReturn(NOW.plusSeconds(299));

        assertThat(recoveryService.recoverExpired(10)).isZero();
        assertThat(notification(claim.notificationId()).getStatus())
                .isEqualTo(NotificationStatus.PROCESSING);

        Instant expiredAt = NOW.plusSeconds(300);
        when(clock.instant()).thenReturn(expiredAt);

        assertThat(recoveryService.recoverExpired(10)).isEqualTo(1);

        Notification failed = assertQuarantined(
                claim,
                "notification-recovery",
                expiredAt
        );

        assertThat(failed.getVersion()).isEqualTo(processingVersion + 1);

        long failedVersion = failed.getVersion();

        assertThat(recoveryService.recoverExpired(10)).isZero();

        assertThat(outcomeService.recordOutcome(
                claim,
                EmailProviderResult.accepted("late-provider-result")
        )).isFalse();

        assertThat(claimService.claimEligible(10)).isEmpty();
        assertThat(notification(claim.notificationId()).getVersion())
                .isEqualTo(failedVersion);

        verifyNoInteractions(emailProvider);
    }

    @Test
    void quarantinesMissingHistoryWithoutCreatingAnAttempt() {
        notificationRepository.saveAndFlush(
                pending(1, NOW.minusSeconds(600))
        );

        // Deliberately inconsistent historical fixture, not application behavior.
        int updated = jdbcTemplate.update("""
            UPDATE notification
            SET status = 'PROCESSING',
                processing_expires_at = NULL,
                last_attempt_at = NULL
            WHERE source_application = ?
              AND idempotency_key = ?
            """, sourceApplication, "key-1");

        assertThat(updated).isEqualTo(1);

        assertThat(recoveryService.recoverExpired(1)).isEqualTo(1);

        Notification stored = notification(id(1));

        assertThat(stored.getStatus())
                .isEqualTo(NotificationStatus.FAILED);
        assertThat(stored.getFailureCode())
                .isEqualTo(ProcessingFailureCodes.HISTORY_MISSING);
        assertThat(stored.getProcessingExpiresAt()).isNull();
        assertThat(stored.getLastAttemptNumber()).isZero();
        assertThat(stored.getCycleAttemptCount()).isZero();

        assertThat(deliveryAttemptRepository.count()).isZero();
        assertThat(recoveryService.recoverExpired(1)).isZero();
        assertThat(claimService.claimEligible(1)).isEmpty();
    }

    @Test
    void rollsBackRecoveryBatchWhenALaterAttemptIsInvalid() {
        notificationRepository.saveAllAndFlush(List.of(
                pending(1, NOW.minusSeconds(60)),
                pending(2, NOW.minusSeconds(30))
        ));

        List<ClaimedNotification> claims = claimService.claimEligible(2);

        assertThat(claims)
                .extracting(ClaimedNotification::notificationId)
                .containsExactly(id(1), id(2));

        List<Notification> before = claims.stream()
                .map(claim -> notification(claim.notificationId()))
                .toList();

        // Fault injection: the second attempt cannot complete at NOW + 300.
        int updated = jdbcTemplate.update("""
            UPDATE delivery_attempt AS attempt
            JOIN notification AS notification_record
              ON notification_record.notification_id =
                 attempt.notification_id
            SET attempt.started_at_time = ?
            WHERE notification_record.source_application = ?
              AND notification_record.idempotency_key = ?
              AND attempt.attempt_number = 1
            """,
                Timestamp.from(NOW.plusSeconds(301)),
                sourceApplication,
                "key-2"
        );

        assertThat(updated).isEqualTo(1);

        when(clock.instant()).thenReturn(NOW.plusSeconds(300));

        assertThatThrownBy(() -> recoveryService.recoverExpired(2))
                .isInstanceOf(IllegalArgumentException.class);

        for (int index = 0; index < claims.size(); index++) {
            ClaimedNotification claim = claims.get(index);
            Notification original = before.get(index);
            Notification stored = notification(claim.notificationId());

            assertThat(stored.getStatus())
                    .isEqualTo(NotificationStatus.PROCESSING);
            assertThat(stored.getVersion()).isEqualTo(original.getVersion());
            assertThat(stored.getProcessingExpiresAt())
                    .isEqualTo(original.getProcessingExpiresAt());
            assertThat(stored.getUpdatedAtTime())
                    .isEqualTo(original.getUpdatedAtTime());
            assertThat(stored.getUpdatedByProcess())
                    .isEqualTo(original.getUpdatedByProcess());
            assertThat(stored.getLastAttemptNumber()).isEqualTo(1);
            assertThat(stored.getCycleAttemptCount()).isEqualTo(1);

            DeliveryAttempt storedAttempt = attempt(claim.attemptId());

            assertThat(storedAttempt.getOutcome()).isNull();
            assertThat(storedAttempt.getCompletedAtTime()).isNull();
            assertThat(storedAttempt.getFailureCode()).isNull();
        }
    }

    @Test
    void rollsBackOutcomeWhenRetryCounterOverflows() {
        notificationRepository.saveAndFlush(
                pending(1, NOW.minusSeconds(30))
        );

        ClaimedNotification claim =
                claimService.claimEligible(1).get(0);

        int updated = jdbcTemplate.update("""
            UPDATE notification
            SET retry_count = ?
            WHERE source_application = ?
              AND idempotency_key = ?
            """, Integer.MAX_VALUE, sourceApplication, "key-1");

        assertThat(updated).isEqualTo(1);

        Notification before = notification(claim.notificationId());

        assertThatThrownBy(() -> outcomeService.recordOutcome(
                claim,
                EmailProviderResult.temporaryFailure("PROVIDER_UNAVAILABLE")
        )).isInstanceOf(ArithmeticException.class);

        Notification stored = notification(claim.notificationId());

        assertThat(stored.getStatus())
                .isEqualTo(NotificationStatus.PROCESSING);
        assertThat(stored.getVersion()).isEqualTo(before.getVersion());
        assertThat(stored.getRetryCount()).isEqualTo(Integer.MAX_VALUE);
        assertThat(stored.getProcessingExpiresAt())
                .isEqualTo(before.getProcessingExpiresAt());
        assertThat(stored.getUpdatedAtTime())
                .isEqualTo(before.getUpdatedAtTime());

        DeliveryAttempt storedAttempt = attempt(claim.attemptId());

        assertThat(storedAttempt.getOutcome()).isNull();
        assertThat(storedAttempt.getCompletedAtTime()).isNull();
        assertThat(storedAttempt.getFailureCode()).isNull();
    }

    @Test
    void serializesRecoveryAgainstALateOutcome() throws Exception {
        notificationRepository.saveAndFlush(
                pending(1, NOW.minusSeconds(30))
        );

        ClaimedNotification claim =
                claimService.claimEligible(1).get(0);

        long processingVersion =
                notification(claim.notificationId()).getVersion();

        Instant expiredAt = NOW.plusSeconds(300);
        when(clock.instant()).thenReturn(expiredAt);

        CountDownLatch start = new CountDownLatch(1);
        concurrentTasks = Executors.newFixedThreadPool(2);

        Future<Integer> recovery = concurrentTasks.submit(() -> {
            awaitRelease(start);
            return recoveryService.recoverExpired(1);
        });

        Future<Boolean> lateOutcome = concurrentTasks.submit(() -> {
            awaitRelease(start);
            return outcomeService.recordOutcome(
                    claim,
                    EmailProviderResult.accepted("late-provider-result")
            );
        });

        start.countDown();

        // Recovery may skip the row while the outcome transaction holds its lock.
        assertThat(recovery.get(10, TimeUnit.SECONDS)).isIn(0, 1);
        assertThat(lateOutcome.get(10, TimeUnit.SECONDS)).isFalse();

        Notification stored = notification(claim.notificationId());

        assertThat(stored.getStatus())
                .isEqualTo(NotificationStatus.FAILED);
        assertThat(stored.getFailureCode())
                .isEqualTo(ProcessingFailureCodes.OUTCOME_UNKNOWN);
        assertThat(stored.getVersion()).isEqualTo(processingVersion + 1);

        DeliveryAttempt completed = attempt(claim.attemptId());

        assertThat(completed.getOutcome())
                .isEqualTo(DeliveryAttemptOutcome.UNKNOWN);
        assertThat(completed.getCompletedAtTime()).isEqualTo(expiredAt);
        assertThat(completed.getProviderMessageId()).isNull();

        assertThat(recoveryService.recoverExpired(1)).isZero();
        assertThat(claimService.claimEligible(1)).isEmpty();
        assertThat(deliveryAttemptRepository.count()).isEqualTo(1);
    }

    private static Stream<Arguments> providerOutcomes() {
        return Stream.of(
                Arguments.of(
                        EmailProviderResult.accepted("provider-message-123"),
                        NotificationStatus.SENT,
                        0
                ),
                Arguments.of(
                        EmailProviderResult.temporaryFailure(
                                "PROVIDER_UNAVAILABLE"
                        ),
                        NotificationStatus.RETRY_PENDING,
                        1
                ),
                Arguments.of(
                        EmailProviderResult.permanentFailure(
                                "INVALID_RECIPIENT"
                        ),
                        NotificationStatus.FAILED,
                        0
                ),
                Arguments.of(
                        EmailProviderResult.unknown("DELIVERY_OUTCOME_UNKNOWN"),
                        NotificationStatus.FAILED,
                        0
                )
        );
    }

    private Notification pending(long value, Instant createdAt) {
        return Notification.create(
                id(value),
                new NotificationKey(
                        sourceApplication,
                        "key-" + value
                ),
                REQUEST_HASH,
                "recipient@example.com",
                "Integration test",
                "Integration test body",
                TEST_PROCESS,
                createdAt
        );
    }

    private void setDueTime(Notification notification, Instant dueAt) {
        int updated = jdbcTemplate.update("""
                UPDATE notification
                SET next_attempt_at = ?
                WHERE source_application = ?
                  AND idempotency_key = ?
                """,
                Timestamp.from(dueAt),
                sourceApplication,
                notification.getIdempotencyKey()
        );

        assertThat(updated).isEqualTo(1);
    }

    private Notification notification(UUID notificationId) {
        return notificationRepository.findById(notificationId)
                .orElseThrow();
    }

    private DeliveryAttempt attempt(UUID attemptId) {
        return deliveryAttemptRepository.findById(attemptId)
                .orElseThrow();
    }

    private Notification assertQuarantined(
            ClaimedNotification claim,
            String expectedProcess,
            Instant completedAt
    ) {
        Notification stored = notification(claim.notificationId());

        assertThat(stored.getStatus())
                .isEqualTo(NotificationStatus.FAILED);
        assertThat(stored.getFailureCode())
                .isEqualTo(ProcessingFailureCodes.OUTCOME_UNKNOWN);
        assertThat(stored.getProcessingExpiresAt()).isNull();
        assertThat(stored.getProviderMessageId()).isNull();
        assertThat(stored.getLastAttemptNumber()).isEqualTo(1);
        assertThat(stored.getCycleAttemptCount()).isEqualTo(1);
        assertThat(stored.getRetryCount()).isZero();
        assertThat(stored.getUpdatedByProcess()).isEqualTo(expectedProcess);
        assertThat(stored.getUpdatedAtTime()).isEqualTo(completedAt);

        DeliveryAttempt completed = attempt(claim.attemptId());

        assertThat(completed.getOutcome())
                .isEqualTo(DeliveryAttemptOutcome.UNKNOWN);
        assertThat(completed.getFailureCode())
                .isEqualTo(ProcessingFailureCodes.OUTCOME_UNKNOWN);
        assertThat(completed.getCompletedAtTime()).isEqualTo(completedAt);
        assertThat(completed.getUpdatedByProcess()).isEqualTo(expectedProcess);
        assertThat(completed.getProviderMessageId()).isNull();

        assertThat(deliveryAttemptRepository.count()).isEqualTo(1);

        return stored;
    }

    private void assertTestDatabase() {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT DATABASE()", String.class
        ))
                .as("Refusing fixture mutations outside the test database")
                .isEqualTo("notification_platform_test");
    }

    private static UUID id(long value) {
        return new UUID(0L, value);
    }

    private static void awaitRelease(CountDownLatch release) {
        try {
            if (!release.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException(
                        "Timed out waiting to release test lock"
                );
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Test lock holder was interrupted",
                    exception
            );
        }
    }
}
