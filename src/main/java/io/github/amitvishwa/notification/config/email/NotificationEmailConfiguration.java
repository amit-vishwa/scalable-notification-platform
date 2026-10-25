package io.github.amitvishwa.notification.config.email;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("!operator-replay & !bootstrap")
@ConditionalOnProperty(
        prefix = "notification.worker",
        name = "enabled",
        havingValue = "true"
)
@EnableConfigurationProperties(EmailProviderProperties.class)
public class NotificationEmailConfiguration {
}