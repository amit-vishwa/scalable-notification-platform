package io.github.amitvishwa.notification.application.processing;

import io.github.amitvishwa.notification.application.port.email.EmailMessage;
import io.github.amitvishwa.notification.application.port.email.EmailProvider;
import io.github.amitvishwa.notification.application.port.email.EmailProviderResult;
import io.github.amitvishwa.notification.domain.model.DeliveryAttempt;
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
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
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

        List<ClaimedNotification> claims = claimService.claimPending(1);

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

        assertThat(claimService.claimPending(1)).isEmpty();
        assertThat(deliveryAttemptRepository.count()).isEqualTo(1);
    }

    @Test
    void ordersDuePendingNotificationsAndRespectsBatchLimit() {
        Notification earliest = pending(4, NOW.minusSeconds(300));
        Notification olderSameDue = pending(3, NOW.minusSeconds(200));
        Notification firstTie = pending(1, NOW.minusSeconds(100));
        Notification secondTie = pending(2, NOW.minusSeconds(100));
        Notification future = pending(5, NOW.plusSeconds(60));

        Notification retry = pending(6, NOW.minusSeconds(300));
        retry.markProcessing(TEST_PROCESS, NOW.minusSeconds(290));
        retry.scheduleRetry(
                "TEMPORARY_ERROR",
                NOW.minusSeconds(200),
                TEST_PROCESS,
                NOW.minusSeconds(280)
        );

        Notification processing = pending(7, NOW.minusSeconds(300));
        processing.markProcessing(TEST_PROCESS, NOW.minusSeconds(100));

        Notification sent = pending(8, NOW.minusSeconds(300));
        sent.markProcessing(TEST_PROCESS, NOW.minusSeconds(100));
        sent.markSent(
                "already-accepted",
                TEST_PROCESS,
                NOW.minusSeconds(90)
        );

        Notification failed = pending(9, NOW.minusSeconds(300));
        failed.markProcessing(TEST_PROCESS, NOW.minusSeconds(100));
        failed.markFailed(
                "INVALID_RECIPIENT",
                TEST_PROCESS,
                NOW.minusSeconds(90)
        );

        notificationRepository.saveAllAndFlush(List.of(
                secondTie,
                failed,
                future,
                retry,
                olderSameDue,
                sent,
                firstTie,
                processing,
                earliest
        ));

        // Independently exercise due-time and creation-time ordering.
        setDueTime(earliest, NOW.minusSeconds(100));
        setDueTime(olderSameDue, NOW.minusSeconds(50));
        setDueTime(firstTie, NOW.minusSeconds(50));
        setDueTime(secondTie, NOW.minusSeconds(50));

        assertThat(claimService.claimPending(3))
                .extracting(ClaimedNotification::notificationId)
                .containsExactly(id(4), id(3), id(1));

        assertThat(notification(id(2)).getStatus())
                .isEqualTo(NotificationStatus.PENDING);

        assertThat(claimService.claimPending(10))
                .extracting(ClaimedNotification::notificationId)
                .containsExactly(id(2));

        assertThat(claimService.claimPending(10)).isEmpty();
        assertThat(deliveryAttemptRepository.count()).isEqualTo(4);

        assertThat(notification(id(5)).getStatus())
                .isEqualTo(NotificationStatus.PENDING);
        assertThat(notification(id(6)).getStatus())
                .isEqualTo(NotificationStatus.RETRY_PENDING);
    }

    @Test
    void rejectsInvalidBatchSizesWithoutCreatingAttempts() {
        notificationRepository.saveAndFlush(
                pending(1, NOW.minusSeconds(30))
        );

        for (int invalidSize : new int[]{-1, 0, 11}) {
            assertThatThrownBy(
                    () -> claimService.claimPending(invalidSize)
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
                    List<Notification> held =
                            notificationRepository.lockPendingBatch(NOW, 1);

                    assertThat(held)
                            .extracting(Notification::getNotificationId)
                            .containsExactly(id(1));

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
                            () -> claimService.claimPending(2)
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

        assertThat(claimService.claimPending(2))
                .extracting(ClaimedNotification::notificationId)
                .containsExactly(id(1));

        assertThat(claimService.claimPending(2)).isEmpty();
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

        assertThatThrownBy(() -> claimService.claimPending(2))
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

        ClaimedNotification claim = claimService.claimPending(1).get(0);

        outcomeService.recordOutcome(claim, result);

        Notification stored = notification(claim.notificationId());
        DeliveryAttempt attempt = attempt(claim.attemptId());

        assertThat(stored.getStatus()).isEqualTo(expectedStatus);
        assertThat(stored.getRetryCount()).isEqualTo(expectedRetryCount);
        assertThat(stored.getFailureCode()).isEqualTo(result.failureCode());
        assertThat(stored.getProviderMessageId())
                .isEqualTo(result.providerMessageId());
        assertThat(stored.getUpdatedByProcess())
                .isEqualTo(WORKER_PROCESS);
        assertThat(stored.getUpdatedAtTime()).isEqualTo(NOW);

        assertThat(attempt.getOutcome()).isEqualTo(result.outcome());
        assertThat(attempt.getCompletedAtTime()).isEqualTo(NOW);
        assertThat(attempt.getFailureCode()).isEqualTo(result.failureCode());
        assertThat(attempt.getProviderMessageId())
                .isEqualTo(result.providerMessageId());
        assertThat(attempt.getUpdatedByProcess())
                .isEqualTo(WORKER_PROCESS);

        if (expectedStatus == NotificationStatus.RETRY_PENDING) {
            assertThat(stored.getNextAttemptAt())
                    .isEqualTo(NOW.plusSeconds(60));

            when(clock.instant()).thenReturn(NOW.plusSeconds(61));

            // P007 records retry intent; P008 will consume RETRY_PENDING.
            assertThat(claimService.claimPending(10)).isEmpty();
        }

        assertThatThrownBy(
                () -> outcomeService.recordOutcome(claim, result)
        ).isInstanceOf(IllegalStateException.class);

        assertThat(deliveryAttemptRepository.count()).isEqualTo(1);
    }

    @Test
    void rollsBackPartialOutcomeMutationWhenCompletionValidationFails() {
        notificationRepository.saveAndFlush(
                pending(1, NOW.minusSeconds(30))
        );

        ClaimedNotification claim = claimService.claimPending(1).get(0);

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