package io.github.amitvishwa.notification.config;

import io.github.amitvishwa.notification.application.processing.NotificationClaimService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "notification.worker")
public record NotificationWorkerProperties(
        @DefaultValue("false")
        boolean enabled,

        @DefaultValue("true")
        boolean pollEnabled,

        @DefaultValue("10")
        @Min(1)
        @Max(NotificationClaimService.MAX_BATCH_SIZE)
        int batchSize,

        @DefaultValue("PT5S")
        @NotNull
        @DurationMin(seconds = 1)
        @DurationMax(minutes = 1)
        Duration pollDelay
) {
}