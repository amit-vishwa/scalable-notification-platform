package io.github.amitvishwa.notification.application.processing;

import io.github.amitvishwa.notification.application.port.email.EmailMessage;
import io.github.amitvishwa.notification.application.port.email.EmailProvider;
import io.github.amitvishwa.notification.application.port.email.EmailProviderResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.TransactionSystemException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationBatchProcessorTest {

    @Mock
    private NotificationClaimService claimService;

    @Mock
    private NotificationOutcomeService outcomeService;

    @Mock
    private NotificationRecoveryService recoveryService;

    @Mock
    private EmailProvider emailProvider;

    private NotificationBatchProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new NotificationBatchProcessor(
                claimService,
                outcomeService,
                recoveryService,
                emailProvider
        );
    }

    @Test
    void processesClaimsSequentiallyAfterRecoveryAndLeaseChecks() {
        ClaimedNotification first = claim();
        ClaimedNotification second = claim();

        EmailProviderResult accepted =
                EmailProviderResult.accepted("provider-message-1");
        EmailProviderResult rejected =
                EmailProviderResult.permanentFailure("INVALID_RECIPIENT");

        givenClaims(first, second);

        when(outcomeService.canSend(first)).thenReturn(true);
        when(outcomeService.canSend(second)).thenReturn(true);
        when(emailProvider.send(first.message())).thenReturn(accepted);
        when(emailProvider.send(second.message())).thenReturn(rejected);
        when(outcomeService.recordOutcome(first, accepted)).thenReturn(true);
        when(outcomeService.recordOutcome(second, rejected)).thenReturn(true);

        processor.processBatch(2);

        InOrder order = inOrder(
                recoveryService,
                claimService,
                outcomeService,
                emailProvider
        );

        order.verify(recoveryService).recoverExpired(2);
        order.verify(claimService).claimEligible(2);
        order.verify(outcomeService).canSend(first);
        order.verify(emailProvider).send(first.message());
        order.verify(outcomeService).recordOutcome(first, accepted);
        order.verify(outcomeService).canSend(second);
        order.verify(emailProvider).send(second.message());
        order.verify(outcomeService).recordOutcome(second, rejected);
        order.verifyNoMoreInteractions();
    }

    @Test
    void doesNotInvokeProviderWhenNoWorkIsEligible() {
        when(claimService.claimEligible(10)).thenReturn(List.of());

        processor.processBatch(10);

        verify(recoveryService).recoverExpired(10);
        verifyNoInteractions(emailProvider, outcomeService);
    }

    @Test
    void skipsInactiveClaimWithoutSending() {
        ClaimedNotification claim = claim();
        givenClaims(claim);

        when(outcomeService.canSend(claim)).thenReturn(false);

        processor.processBatch(1);

        verifyNoInteractions(emailProvider);
        verify(outcomeService, never()).recordOutcome(any(), any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void recordsUnknownProviderOutcomeAndContinues(boolean throwsFailure) {
        ClaimedNotification first = claim();
        ClaimedNotification second = claim();

        EmailProviderResult unknown =
                EmailProviderResult.unknown("DELIVERY_OUTCOME_UNKNOWN");
        EmailProviderResult accepted = EmailProviderResult.accepted(null);

        givenClaims(first, second);

        when(outcomeService.canSend(first)).thenReturn(true);
        when(outcomeService.canSend(second)).thenReturn(true);

        if (throwsFailure) {
            when(emailProvider.send(first.message()))
                    .thenThrow(new IllegalStateException(
                            "Simulated provider failure"
                    ));
        } else {
            when(emailProvider.send(first.message())).thenReturn(null);
        }

        when(emailProvider.send(second.message())).thenReturn(accepted);
        when(outcomeService.recordOutcome(first, unknown)).thenReturn(true);
        when(outcomeService.recordOutcome(second, accepted)).thenReturn(true);

        processor.processBatch(2);

        verify(outcomeService).recordOutcome(first, unknown);
        verify(outcomeService).recordOutcome(second, accepted);
        verify(emailProvider).send(second.message());
    }

    @Test
    void doesNotResendWhenOutcomeIsIgnored() {
        ClaimedNotification claim = claim();
        EmailProviderResult accepted = EmailProviderResult.accepted(null);

        givenClaims(claim);

        when(outcomeService.canSend(claim)).thenReturn(true);
        when(emailProvider.send(claim.message())).thenReturn(accepted);
        when(outcomeService.recordOutcome(claim, accepted)).thenReturn(false);

        processor.processBatch(1);

        verify(emailProvider).send(claim.message());
        verify(outcomeService).recordOutcome(claim, accepted);
    }

    @Test
    void abortsBatchAfterDatabaseFailure() {
        assertAbortsAfterPersistenceFailure(
                new DataIntegrityViolationException("Simulated DB failure")
        );
    }

    @Test
    void abortsBatchAfterTransactionFailure() {
        assertAbortsAfterPersistenceFailure(
                new TransactionSystemException("Simulated transaction failure")
        );
    }

    @Test
    void doesNotClaimWorkWhenRecoveryFails() {
        RuntimeException failure =
                new DataIntegrityViolationException(
                        "Simulated recovery failure"
                );

        when(recoveryService.recoverExpired(2)).thenThrow(failure);

        assertThatThrownBy(() -> processor.processBatch(2))
                .isSameAs(failure);

        verifyNoInteractions(
                claimService,
                outcomeService,
                emailProvider
        );
    }

    @Test
    void doesNothingAfterShutdownStarts() {
        processor.stopOnShutdown();

        processor.processBatch(10);

        verifyNoInteractions(
                recoveryService,
                claimService,
                outcomeService,
                emailProvider
        );
    }

    @Test
    void recordsInFlightOutcomeButDoesNotSendNextClaimAfterShutdown() {
        ClaimedNotification first = claim();
        ClaimedNotification second = claim();
        EmailProviderResult accepted = EmailProviderResult.accepted(null);

        givenClaims(first, second);

        when(outcomeService.canSend(first)).thenReturn(true);
        when(emailProvider.send(first.message())).thenAnswer(invocation -> {
            processor.stopOnShutdown();
            return accepted;
        });
        when(outcomeService.recordOutcome(first, accepted)).thenReturn(true);

        processor.processBatch(2);

        verify(outcomeService).recordOutcome(first, accepted);
        verify(outcomeService, never()).canSend(second);
        verify(emailProvider, never()).send(second.message());
    }

    private void assertAbortsAfterPersistenceFailure(
            RuntimeException failure
    ) {
        ClaimedNotification first = claim();
        ClaimedNotification second = claim();
        EmailProviderResult accepted = EmailProviderResult.accepted(null);

        givenClaims(first, second);

        when(outcomeService.canSend(first)).thenReturn(true);
        when(emailProvider.send(first.message())).thenReturn(accepted);
        when(outcomeService.recordOutcome(first, accepted))
                .thenThrow(failure);

        assertThatThrownBy(() -> processor.processBatch(2))
                .isSameAs(failure);

        verify(outcomeService, never()).canSend(second);
        verify(emailProvider, never()).send(second.message());
        verify(outcomeService, never()).recordOutcome(
                first,
                EmailProviderResult.unknown("DELIVERY_OUTCOME_UNKNOWN")
        );
    }

    private void givenClaims(ClaimedNotification... claims) {
        when(claimService.claimEligible(claims.length))
                .thenReturn(List.of(claims));
    }

    private ClaimedNotification claim() {
        return new ClaimedNotification(
                UUID.randomUUID(),
                new EmailMessage(
                        UUID.randomUUID(),
                        "recipient@example.com",
                        "Test notification",
                        "Test notification body"
                )
        );
    }
}