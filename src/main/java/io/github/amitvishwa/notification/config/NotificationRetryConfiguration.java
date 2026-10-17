package io.github.amitvishwa.notification.config;

import io.github.amitvishwa.notification.domain.policy.RetryPolicy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NotificationRetryProperties.class)
public class NotificationRetryConfiguration {

    @Bean
    public RetryPolicy retryPolicy(
            NotificationRetryProperties properties
    ) {
        return new RetryPolicy(
                properties.maxAttempts(),
                properties.initialDelay(),
                properties.maxDelay()
        );
    }
}