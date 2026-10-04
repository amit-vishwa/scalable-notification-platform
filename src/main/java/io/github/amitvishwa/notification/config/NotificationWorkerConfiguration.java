package io.github.amitvishwa.notification.config;

import io.github.amitvishwa.notification.application.port.email.EmailProvider;
import io.github.amitvishwa.notification.application.processing.NotificationBatchProcessor;
import io.github.amitvishwa.notification.application.processing.NotificationClaimService;
import io.github.amitvishwa.notification.application.processing.NotificationOutcomeService;
import io.github.amitvishwa.notification.worker.NotificationWorkerScheduler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(NotificationWorkerProperties.class)
public class NotificationWorkerConfiguration {

    @Bean
    @ConditionalOnProperty(
            prefix = "notification.worker",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = false
    )
    public NotificationBatchProcessor notificationBatchProcessor(
            NotificationClaimService claimService,
            NotificationOutcomeService outcomeService,
            EmailProvider emailProvider
    ) {
        return new NotificationBatchProcessor(
                claimService,
                outcomeService,
                emailProvider
        );
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "notification.worker",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = false
    )
    @ConditionalOnProperty(
            prefix = "notification.worker",
            name = "poll-enabled",
            havingValue = "true",
            matchIfMissing = true
    )
    public NotificationWorkerScheduler notificationWorkerScheduler(
            NotificationBatchProcessor processor,
            NotificationWorkerProperties properties
    ) {
        return new NotificationWorkerScheduler(
                processor,
                properties
        );
    }
}