package io.github.amitvishwa.notification.application.processing;

import io.github.amitvishwa.notification.application.port.email.EmailMessage;
import io.github.amitvishwa.notification.application.port.email.EmailProvider;
import io.github.amitvishwa.notification.application.port.email.EmailProviderResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.TransactionSystemException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationBatchProcessorTest {

    @Mock
    private NotificationClaimService claimService;

    @Mock
    private NotificationOutcomeService outcomeService;

    @Mock
    private EmailProvider emailProvider;

    private NotificationBatchProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new NotificationBatchProcessor(
                claimService,
                outcomeService,
                emailProvider
        );
    }

    @Test
    void processesClaimsSequentiallyAndPersistsEachOutcome() {
        ClaimedNotification first = claim();
        ClaimedNotification second = claim();

        EmailProviderResult accepted =
                EmailProviderResult.accepted("provider-message-1");
        EmailProviderResult rejected =
                EmailProviderResult.permanentFailure("INVALID_RECIPIENT");

        when(claimService.claimPending(2))
                .thenReturn(List.of(first, second));
        when(emailProvider.send(first.message())).thenReturn(accepted);
        when(emailProvider.send(second.message())).thenReturn(rejected);

        processor.processBatch(2);

        InOrder order = inOrder(
                claimService,
                emailProvider,
                outcomeService
        );

        order.verify(claimService).claimPending(2);
        order.verify(emailProvider).send(first.message());
        order.verify(outcomeService).recordOutcome(first, accepted);
        order.verify(emailProvider).send(second.message());
        order.verify(outcomeService).recordOutcome(second, rejected);
        order.verifyNoMoreInteractions();
    }

    @Test
    void doesNothingWhenNoNotificationsAreEligible() {
        when(claimService.claimPending(10)).thenReturn(List.of());

        processor.processBatch(10);

        verifyNoInteractions(emailProvider, outcomeService);
    }

    @Test
    void continuesAfterUnexpectedProviderFailureWithoutInventingAnOutcome() {
        ClaimedNotification first = claim();
        ClaimedNotification second = claim();
        EmailProviderResult accepted = EmailProviderResult.accepted(null);

        when(claimService.claimPending(2))
                .thenReturn(List.of(first, second));
        when(emailProvider.send(first.message()))
                .thenThrow(new IllegalStateException("Unexpected failure"));
        when(emailProvider.send(second.message())).thenReturn(accepted);

        processor.processBatch(2);

        verify(emailProvider).send(first.message());
        verify(emailProvider).send(second.message());
        verify(outcomeService).recordOutcome(second, accepted);
        verifyNoMoreInteractions(outcomeService);
    }

    @Test
    void treatsNullProviderResultAsUnexpectedFailureAndContinues() {
        ClaimedNotification first = claim();
        ClaimedNotification second = claim();
        EmailProviderResult accepted = EmailProviderResult.accepted(null);

        when(claimService.claimPending(2))
                .thenReturn(List.of(first, second));
        when(emailProvider.send(first.message())).thenReturn(null);
        when(emailProvider.send(second.message())).thenReturn(accepted);

        processor.processBatch(2);

        verify(outcomeService).recordOutcome(second, accepted);
        verifyNoMoreInteractions(outcomeService);
    }

    @Test
    void abortsBatchAfterDatabaseFailure() {
        assertAbortsAfterPersistenceFailure(
                new DataIntegrityViolationException(
                        "Simulated constraint failure"
                )
        );
    }

    @Test
    void abortsBatchAfterTransactionFailure() {
        assertAbortsAfterPersistenceFailure(
                new TransactionSystemException(
                        "Simulated transaction failure"
                )
        );
    }

    @Test
    void doesNotClaimMoreWorkAfterShutdownStarts() {
        processor.stopOnShutdown();

        processor.processBatch(10);

        verifyNoInteractions(
                claimService,
                emailProvider,
                outcomeService
        );
    }

    @Test
    void recordsInFlightOutcomeButStopsBeforeSendingNextNotification() {
        ClaimedNotification first = claim();
        ClaimedNotification second = claim();
        EmailProviderResult accepted = EmailProviderResult.accepted(null);

        when(claimService.claimPending(2))
                .thenReturn(List.of(first, second));

        when(emailProvider.send(first.message())).thenAnswer(invocation -> {
            processor.stopOnShutdown();
            return accepted;
        });

        processor.processBatch(2);

        verify(outcomeService).recordOutcome(first, accepted);
        verify(emailProvider, never()).send(second.message());
        verifyNoMoreInteractions(outcomeService);
    }

    private void assertAbortsAfterPersistenceFailure(
            RuntimeException failure
    ) {
        ClaimedNotification first = claim();
        ClaimedNotification second = claim();
        EmailProviderResult accepted = EmailProviderResult.accepted(null);

        when(claimService.claimPending(2))
                .thenReturn(List.of(first, second));
        when(emailProvider.send(first.message())).thenReturn(accepted);

        doThrow(failure)
                .when(outcomeService)
                .recordOutcome(first, accepted);

        assertThatThrownBy(() -> processor.processBatch(2))
                .isSameAs(failure);

        verify(emailProvider, never()).send(second.message());
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