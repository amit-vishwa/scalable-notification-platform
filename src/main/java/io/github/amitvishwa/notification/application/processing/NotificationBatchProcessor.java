package io.github.amitvishwa.notification.application.processing;

import io.github.amitvishwa.notification.application.port.email.EmailProvider;
import io.github.amitvishwa.notification.application.port.email.EmailProviderResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

public class NotificationBatchProcessor {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(NotificationBatchProcessor.class);

    private final NotificationClaimService claimService;
    private final NotificationOutcomeService outcomeService;
    private final EmailProvider emailProvider;

    private volatile boolean stopping;

    public NotificationBatchProcessor(
            NotificationClaimService claimService,
            NotificationOutcomeService outcomeService,
            EmailProvider emailProvider
    ) {
        this.claimService = claimService;
        this.outcomeService = outcomeService;
        this.emailProvider = emailProvider;

        LOGGER.info(
                "Notification worker initialized; provider={}",
                emailProvider.getClass().getSimpleName()
        );
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void processBatch(int batchSize) {
        if (isStopping()) {
            return;
        }

        List<ClaimedNotification> claims =
                claimService.claimPending(batchSize);

        for (ClaimedNotification claim : claims) {
            if (isStopping()) {
                return;
            }

            try {
                EmailProviderResult result = Objects.requireNonNull(
                        emailProvider.send(claim.message()),
                        "Email provider returned no result"
                );

                outcomeService.recordOutcome(claim, result);

                LOGGER.info(
                        "Notification outcome persisted: "
                                + "notificationId={}, attemptId={}, outcome={}",
                        claim.notificationId(),
                        claim.attemptId(),
                        result.outcome()
                );
            } catch (DataAccessException | TransactionException exception) {
                LOGGER.error(
                        "Stopping batch after persistence failure: "
                                + "notificationId={}, attemptId={}, errorType={}",
                        claim.notificationId(),
                        claim.attemptId(),
                        exception.getClass().getSimpleName()
                );

                throw exception;
            } catch (RuntimeException exception) {
                LOGGER.error(
                        "Notification processing failed: "
                                + "notificationId={}, attemptId={}, errorType={}",
                        claim.notificationId(),
                        claim.attemptId(),
                        exception.getClass().getSimpleName()
                );
            }
        }
    }

    @EventListener(ContextClosedEvent.class)
    public void stopOnShutdown() {
        stopping = true;
    }

    private boolean isStopping() {
        return stopping || Thread.currentThread().isInterrupted();
    }
}