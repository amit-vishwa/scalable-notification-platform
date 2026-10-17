package io.github.amitvishwa.notification.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ApiKeyProperties.class)
@ConditionalOnWebApplication(
        type = ConditionalOnWebApplication.Type.SERVLET
)
public class ApiSecurityConfiguration {
}