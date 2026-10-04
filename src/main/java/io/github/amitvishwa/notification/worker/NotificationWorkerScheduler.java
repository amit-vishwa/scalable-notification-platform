package io.github.amitvishwa.notification.worker;

import io.github.amitvishwa.notification.application.processing.NotificationBatchProcessor;
import io.github.amitvishwa.notification.config.NotificationWorkerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

public class NotificationWorkerScheduler {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(NotificationWorkerScheduler.class);

    private final NotificationBatchProcessor processor;
    private final NotificationWorkerProperties properties;

    public NotificationWorkerScheduler(
            NotificationBatchProcessor processor,
            NotificationWorkerProperties properties
    ) {
        this.processor = processor;
        this.properties = properties;
    }

    @Scheduled(
            fixedDelayString =
                    "${notification.worker.poll-delay:PT5S}",
            initialDelayString =
                    "${notification.worker.poll-delay:PT5S}"
    )
    public void poll() {
        try {
            processor.processBatch(properties.batchSize());
        } catch (RuntimeException exception) {
            LOGGER.error(
                    "Notification worker poll aborted: errorType={}",
                    exception.getClass().getSimpleName()
            );
        }
    }
}