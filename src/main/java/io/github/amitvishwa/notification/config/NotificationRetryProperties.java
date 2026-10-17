package io.github.amitvishwa.notification.config;

import io.github.amitvishwa.notification.domain.policy.RetryPolicy;
import jakarta.validation.constraints.AssertTrue;
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
@ConfigurationProperties(prefix = "notification.retry")
public record NotificationRetryProperties(
        @DefaultValue("5")
        @Min(1)
        @Max(RetryPolicy.MAX_ATTEMPTS)
        int maxAttempts,

        @DefaultValue("PT1M")
        @NotNull
        @DurationMin(seconds = 1)
        @DurationMax(hours = 24)
        Duration initialDelay,

        @DefaultValue("PT15M")
        @NotNull
        @DurationMin(seconds = 1)
        @DurationMax(hours = 24)
        Duration maxDelay,

        @DefaultValue("PT5M")
        @NotNull
        @DurationMin(seconds = 30)
        @DurationMax(hours = 1)
        Duration processingLease
) {

    @AssertTrue(message = "initialDelay must not exceed maxDelay")
    public boolean isDelayRangeValid() {
        return initialDelay == null
                || maxDelay == null
                || initialDelay.compareTo(maxDelay) <= 0;
    }
}