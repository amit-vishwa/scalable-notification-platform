package io.github.amitvishwa.notification.config.email;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "notification.email")
public record EmailProviderProperties(
        @DefaultValue("SIMULATED")
        @NotNull
        EmailProviderType provider
) {
}