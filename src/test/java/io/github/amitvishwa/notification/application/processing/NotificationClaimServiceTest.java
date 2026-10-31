package io.github.amitvishwa.notification.application.processing;

import io.github.amitvishwa.notification.application.preference.PreferenceDecision;
import io.github.amitvishwa.notification.application.preference.PreferenceEvaluationService;
import io.github.amitvishwa.notification.config.NotificationRetryProperties;
import io.github.amitvishwa.notification.domain.model.DeliveryAttempt;
import io.github.amitvishwa.notification.domain.model.Notification;
import io.github.amitvishwa.notification.domain.model.NotificationKey;
import io.github.amitvishwa.notification.domain.model.NotificationStatus;
import io.github.amitvishwa.notification.domain.model.SuppressionReason;
import io.github.amitvishwa.notification.domain.policy.RetryPolicy;
import io.github.amitvishwa.notification.domain.preference.PreferenceKey;
import io.github.amitvishwa.notification.infrastructure.persistence.DeliveryAttemptRepository;
import io.github.amitvishwa.notification.infrastructure.persistence.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationClaimServiceTest {

    private static final Instant NOW =
            Instant.parse("2026-10-31T08:00:00Z");

    private static final int MAX_ATTEMPTS = 3;

    private static final Duration LEASE = Duration.ofMinutes(5);

    @Mock
    private NotificationRepository notifications;

    @Mock
    private DeliveryAttemptRepository attempts;

    @Mock
    private PreferenceEvaluationService preferenceEvaluation;

    private NotificationClaimService service;

    @BeforeEach
    void setUp() {
        RetryPolicy retryPolicy = new RetryPolicy(
                MAX_ATTEMPTS,
                Duration.ofMinutes(1),
                Duration.ofMinutes(15)
        );

        NotificationRetryProperties retryProperties =
                new NotificationRetryProperties(
                        MAX_ATTEMPTS,
                        Duration.ofMinutes(1),
                        Duration.ofMinutes(15),
                        LEASE
                );

        service = new NotificationClaimService(
                notifications,
                attempts,
                preferenceEvaluation,
                retryPolicy,
                retryProperties,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 11})
    void rejectsInvalidBatchSizeBeforeAccessingRepositories(int size) {
        assertThatThrownBy(() -> service.claimEligible(size))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(
                notifications,
                attempts,
                preferenceEvaluation
        );
    }

    @Test
    void returnsNoClaimsWhenNothingIsEligible() {
        when(notifications.lockEligibleBatch(NOW, 1))
                .thenReturn(List.of());

        assertThat(service.claimEligible(1)).isEmpty();

        verifyNoInteractions(preferenceEvaluation);
        verify(attempts, never()).save(any(DeliveryAttempt.class));
        verify(attempts).flush();
    }

    @ParameterizedTest
    @EnumSource(SuppressionReason.class)
    void suppressesWithoutCreatingAnAttempt(SuppressionReason reason) {
        Notification notification = newNotification(NOW);
        givenNotification(notification);

        when(preferenceEvaluation.evaluate(keyOf(notification), NOW))
                .thenReturn(new PreferenceDecision.Suppressed(reason));

        assertThat(service.claimEligible(1)).isEmpty();

        assertThat(notification.getStatus())
                .isEqualTo(NotificationStatus.SUPPRESSED);
        assertThat(notification.getSuppressionReason()).isEqualTo(reason);
        assertThat(notification.getFailureCode()).isNull();

        assertNoAttemptWasConsumed(notification);

        verify(attempts, never()).save(any(DeliveryAttempt.class));
        verify(attempts).flush();
    }

    @Test
    void defersWithoutCreatingAnAttemptOrConsumingRetryBudget() {
        Notification notification = newNotification(NOW);
        Instant nextEligibleAt = NOW.plusSeconds(3_600);

        givenNotification(notification);

        when(preferenceEvaluation.evaluate(keyOf(notification), NOW))
                .thenReturn(
                        new PreferenceDecision.Deferred(nextEligibleAt)
                );

        assertThat(service.claimEligible(1)).isEmpty();

        assertThat(notification.getStatus())
                .isEqualTo(NotificationStatus.PENDING);
        assertThat(notification.getNextAttemptAt())
                .isEqualTo(nextEligibleAt);
        assertThat(notification.getSuppressionReason()).isNull();

        assertNoAttemptWasConsumed(notification);

        verify(attempts, never()).save(any(DeliveryAttempt.class));
        verify(attempts).flush();
    }

    @Test
    void createsAnAttemptOnlyAfterExplicitAllowDecision() {
        Notification notification = newNotification(NOW);
        givenNotification(notification);

        when(preferenceEvaluation.evaluate(keyOf(notification), NOW))
                .thenReturn(PreferenceDecision.Allowed.INSTANCE);

        when(attempts.save(any(DeliveryAttempt.class)))
                .thenAnswer(invocation ->
                        invocation.getArgument(0, DeliveryAttempt.class)
                );

        List<ClaimedNotification> claims = service.claimEligible(1);

        assertThat(claims).hasSize(1);

        ArgumentCaptor<DeliveryAttempt> captor =
                ArgumentCaptor.forClass(DeliveryAttempt.class);

        verify(attempts).save(captor.capture());

        DeliveryAttempt attempt = captor.getValue();
        ClaimedNotification claim = claims.get(0);

        assertThat(attempt.getNotificationId())
                .isEqualTo(notification.getNotificationId());
        assertThat(attempt.getAttemptNumber()).isEqualTo(1);

        assertThat(claim.attemptId()).isEqualTo(attempt.getAttemptId());
        assertThat(claim.notificationId())
                .isEqualTo(notification.getNotificationId());
        assertThat(claim.message().recipientEmail())
                .isEqualTo(notification.getRecipientEmail());
        assertThat(claim.message().subject())
                .isEqualTo(notification.getSubject());
        assertThat(claim.message().body())
                .isEqualTo(notification.getBody());

        assertThat(notification.getStatus())
                .isEqualTo(NotificationStatus.PROCESSING);
        assertThat(notification.getLastAttemptNumber()).isEqualTo(1);
        assertThat(notification.getCycleAttemptCount()).isEqualTo(1);
        assertThat(notification.getRetryCount()).isZero();
        assertThat(notification.getProcessingExpiresAt())
                .isEqualTo(NOW.plus(LEASE));

        InOrder order = inOrder(
                notifications,
                preferenceEvaluation,
                attempts
        );

        order.verify(notifications).lockEligibleBatch(NOW, 1);
        order.verify(preferenceEvaluation)
                .evaluate(keyOf(notification), NOW);
        order.verify(attempts).save(attempt);
        order.verify(attempts).flush();
        order.verifyNoMoreInteractions();
    }

    @Test
    void propagatesEvaluationFailureWithoutStartingAnAttempt() {
        Notification notification = newNotification(NOW);
        givenNotification(notification);

        IllegalStateException failure =
                new IllegalStateException(
                        "Simulated preference evaluation failure"
                );

        when(preferenceEvaluation.evaluate(keyOf(notification), NOW))
                .thenThrow(failure);

        assertThatThrownBy(() -> service.claimEligible(1))
                .isSameAs(failure);

        assertThat(notification.getStatus())
                .isEqualTo(NotificationStatus.PENDING);
        assertNoAttemptWasConsumed(notification);

        verifyNoInteractions(attempts);
    }

    @Test
    void rejectsNullDecisionInsteadOfAllowingDelivery() {
        Notification notification = newNotification(NOW);
        givenNotification(notification);

        when(preferenceEvaluation.evaluate(keyOf(notification), NOW))
                .thenReturn(null);

        assertThatThrownBy(() -> service.claimEligible(1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Unsupported preference decision");

        assertThat(notification.getStatus())
                .isEqualTo(NotificationStatus.PENDING);
        assertNoAttemptWasConsumed(notification);

        verifyNoInteractions(attempts);
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 0})
    void rejectsNonFutureDeferralWithoutStartingAnAttempt(
            long secondsFromNow
    ) {
        Notification notification = newNotification(NOW);
        givenNotification(notification);

        when(preferenceEvaluation.evaluate(keyOf(notification), NOW))
                .thenReturn(new PreferenceDecision.Deferred(
                        NOW.plusSeconds(secondsFromNow)
                ));

        assertThatThrownBy(() -> service.claimEligible(1))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(notification.getStatus())
                .isEqualTo(NotificationStatus.PENDING);
        assertThat(notification.getNextAttemptAt()).isEqualTo(NOW);
        assertNoAttemptWasConsumed(notification);

        verifyNoInteractions(attempts);
    }

    @Test
    void failsExhaustedWorkBeforeEvaluatingPreferences() {
        Notification notification = exhaustedNotification();
        givenNotification(notification);

        assertThat(service.claimEligible(1)).isEmpty();

        assertThat(notification.getStatus())
                .isEqualTo(NotificationStatus.FAILED);
        assertThat(notification.getFailureCode())
                .isEqualTo(ProcessingFailureCodes.RETRY_EXHAUSTED);
        assertThat(notification.getCycleAttemptCount())
                .isEqualTo(MAX_ATTEMPTS);

        verifyNoInteractions(preferenceEvaluation);
        verify(attempts, never()).save(any(DeliveryAttempt.class));
        verify(attempts).flush();
    }

    private void givenNotification(Notification notification) {
        when(notifications.lockEligibleBatch(NOW, 1))
                .thenReturn(List.of(notification));
    }

    private static PreferenceKey keyOf(Notification notification) {
        return new PreferenceKey(
                notification.getSourceApplication(),
                notification.getRecipientEmail()
        );
    }

    private static void assertNoAttemptWasConsumed(
            Notification notification
    ) {
        assertThat(notification.getRetryCount()).isZero();
        assertThat(notification.getCycleAttemptCount()).isZero();
        assertThat(notification.getLastAttemptNumber()).isZero();
        assertThat(notification.getLastAttemptAt()).isNull();
        assertThat(notification.getProcessingExpiresAt()).isNull();
    }

    private static Notification exhaustedNotification() {
        Instant attemptAt = NOW.minusSeconds(300);
        Notification notification = newNotification(attemptAt);

        for (int index = 0; index < MAX_ATTEMPTS; index++) {
            notification.markProcessing(
                    "test-worker",
                    attemptAt,
                    attemptAt.plus(LEASE)
            );

            notification.scheduleRetry(
                    "TEMPORARY_ERROR",
                    attemptAt.plusSeconds(60),
                    "test-worker",
                    attemptAt.plusSeconds(1)
            );

            attemptAt = attemptAt.plusSeconds(60);
        }

        return notification;
    }

    private static Notification newNotification(Instant createdAt) {
        return Notification.create(
                UUID.randomUUID(),
                new NotificationKey(
                        "employee-service",
                        "p010-" + UUID.randomUUID()
                ),
                "a".repeat(64),
                "recipient@example.invalid",
                "Preference unit test",
                "Synthetic unit-test message.",
                "preference-unit-test",
                createdAt
        );
    }
}