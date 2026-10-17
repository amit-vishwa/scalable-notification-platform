package io.github.amitvishwa.notification.domain.policy;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

public record RetryPolicy(
        int maxAttempts,
        Duration initialDelay,
        Duration maxDelay
) {

    public static final int MAX_ATTEMPTS = 100;

    public RetryPolicy {
        if (maxAttempts < 1 || maxAttempts > MAX_ATTEMPTS) {
            throw new IllegalArgumentException(
                    "maxAttempts must be between 1 and " + MAX_ATTEMPTS
            );
        }

        Objects.requireNonNull(
                initialDelay,
                "initialDelay must not be null"
        );
        Objects.requireNonNull(
                maxDelay,
                "maxDelay must not be null"
        );

        if (initialDelay.compareTo(Duration.ZERO) <= 0
                || maxDelay.compareTo(Duration.ZERO) <= 0) {
            throw new IllegalArgumentException(
                    "Retry delays must be positive"
            );
        }

        if (initialDelay.compareTo(maxDelay) > 0) {
            throw new IllegalArgumentException(
                    "initialDelay must not exceed maxDelay"
            );
        }
    }

    public Optional<Duration> delayAfter(int completedAttempts) {
        if (completedAttempts < 1) {
            throw new IllegalArgumentException(
                    "completedAttempts must be at least 1"
            );
        }

        if (completedAttempts >= maxAttempts) {
            return Optional.empty();
        }

        Duration delay = initialDelay;
        Duration halfMaximum = maxDelay.dividedBy(2);

        for (int attempt = 1;
             attempt < completedAttempts && delay.compareTo(maxDelay) < 0;
             attempt++) {

            // Compare before multiplying to avoid Duration overflow.
            delay = delay.compareTo(halfMaximum) > 0
                    ? maxDelay
                    : delay.multipliedBy(2);
        }

        return Optional.of(delay);
    }
}