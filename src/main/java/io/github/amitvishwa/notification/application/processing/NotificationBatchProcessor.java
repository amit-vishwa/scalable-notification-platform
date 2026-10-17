package io.github.amitvishwa.notification.application.processing;

import io.github.amitvishwa.notification.application.port.email.EmailProvider;
import io.github.amitvishwa.notification.application.port.email.EmailProviderResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public class NotificationBatchProcessor {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(NotificationBatchProcessor.class);

    private final NotificationClaimService claimService;
    private final NotificationOutcomeService outcomeService;
    private final NotificationRecoveryService recoveryService;
    private final EmailProvider emailProvider;

    private volatile boolean stopping;

    public NotificationBatchProcessor(
            NotificationClaimService claimService,
            NotificationOutcomeService outcomeService,
            NotificationRecoveryService recoveryService,
            EmailProvider emailProvider
    ) {
        this.claimService = claimService;
        this.outcomeService = outcomeService;
        this.recoveryService = recoveryService;
        this.emailProvider = emailProvider;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void processBatch(int batchSize) {
        if (isStopping()) {
            return;
        }

        int recovered = recoveryService.recoverExpired(batchSize);

        if (recovered > 0) {
            LOGGER.warn(
                    "Recovered expired processing claims: count={}",
                    recovered
            );
        }

        if (isStopping()) {
            return;
        }

        List<ClaimedNotification> claims =
                claimService.claimEligible(batchSize);

        for (ClaimedNotification claim : claims) {
            if (isStopping()) {
                return;
            }

            try {
                if (!outcomeService.canSend(claim)) {
                    LOGGER.warn(
                            "Skipping inactive claim: "
                                    + "notificationId={}, attemptId={}",
                            claim.notificationId(),
                            claim.attemptId()
                    );
                    continue;
                }

                if (isStopping()) {
                    return;
                }

                EmailProviderResult result = invokeProvider(claim);

                boolean applied =
                        outcomeService.recordOutcome(claim, result);

                if (applied) {
                    LOGGER.info(
                            "Notification outcome persisted: "
                                    + "notificationId={}, attemptId={}, "
                                    + "outcome={}",
                            claim.notificationId(),
                            claim.attemptId(),
                            result.outcome()
                    );
                } else {
                    LOGGER.warn(
                            "Ignoring inactive claim result: "
                                    + "notificationId={}, attemptId={}, "
                                    + "outcome={}",
                            claim.notificationId(),
                            claim.attemptId(),
                            result.outcome()
                    );
                }
            } catch (RuntimeException exception) {
                LOGGER.error(
                        "Aborting batch after processing-state failure: "
                                + "notificationId={}, attemptId={}, "
                                + "errorType={}",
                        claim.notificationId(),
                        claim.attemptId(),
                        exception.getClass().getSimpleName()
                );

                throw exception;
            }
        }
    }

    private EmailProviderResult invokeProvider(
            ClaimedNotification claim
    ) {
        try {
            EmailProviderResult result =
                    emailProvider.send(claim.message());

            if (result != null) {
                return result;
            }

            LOGGER.error(
                    "Provider returned no result: "
                            + "notificationId={}, attemptId={}",
                    claim.notificationId(),
                    claim.attemptId()
            );
        } catch (RuntimeException exception) {
            LOGGER.error(
                    "Provider outcome is uncertain: "
                            + "notificationId={}, attemptId={}, "
                            + "errorType={}",
                    claim.notificationId(),
                    claim.attemptId(),
                    exception.getClass().getSimpleName()
            );
        }

        return EmailProviderResult.unknown(
                ProcessingFailureCodes.OUTCOME_UNKNOWN
        );
    }

    @EventListener(ContextClosedEvent.class)
    public void stopOnShutdown() {
        stopping = true;
    }

    private boolean isStopping() {
        return stopping || Thread.currentThread().isInterrupted();
    }
}