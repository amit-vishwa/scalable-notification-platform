package io.github.amitvishwa.notification.domain.policy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetryPolicyTest {

    private final RetryPolicy policy = new RetryPolicy(
            5,
            Duration.ofMinutes(1),
            Duration.ofMinutes(15)
    );

    @ParameterizedTest
    @CsvSource({
            "1, 60",
            "2, 120",
            "3, 240",
            "4, 480"
    })
    void calculatesExponentialDelay(
            int completedAttempts,
            long expectedSeconds
    ) {
        assertThat(policy.delayAfter(completedAttempts))
                .contains(Duration.ofSeconds(expectedSeconds));
    }

    @ParameterizedTest
    @ValueSource(ints = {5, 6, Integer.MAX_VALUE})
    void stopsWhenAttemptBudgetIsExhausted(int completedAttempts) {
        assertThat(policy.delayAfter(completedAttempts)).isEmpty();
    }

    @Test
    void capsDelayAtConfiguredMaximum() {
        RetryPolicy capped = new RetryPolicy(
                100,
                Duration.ofMinutes(1),
                Duration.ofMinutes(15)
        );

        assertThat(capped.delayAfter(5))
                .contains(Duration.ofMinutes(15));
        assertThat(capped.delayAfter(99))
                .contains(Duration.ofMinutes(15));
    }

    @Test
    void supportsOneAttemptWithoutAutomaticRetries() {
        RetryPolicy singleAttempt = new RetryPolicy(
                1,
                Duration.ofSeconds(1),
                Duration.ofSeconds(1)
        );

        assertThat(singleAttempt.delayAfter(1)).isEmpty();
    }

    @Test
    void supportsEqualInitialAndMaximumDelays() {
        RetryPolicy fixed = new RetryPolicy(
                5,
                Duration.ofSeconds(10),
                Duration.ofSeconds(10)
        );

        assertThat(fixed.delayAfter(4))
                .contains(Duration.ofSeconds(10));
    }

    @Test
    void capsBeforeDurationMultiplicationCanOverflow() {
        Duration maximum = Duration.ofSeconds(Long.MAX_VALUE);

        RetryPolicy large = new RetryPolicy(
                5,
                Duration.ofSeconds(Long.MAX_VALUE / 2 + 1),
                maximum
        );

        assertThat(large.delayAfter(2)).contains(maximum);
    }

    @Test
    void handlesAnOddNanosecondCap() {
        RetryPolicy small = new RetryPolicy(
                5,
                Duration.ofNanos(2),
                Duration.ofNanos(3)
        );

        assertThat(small.delayAfter(2))
                .contains(Duration.ofNanos(3));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void rejectsInvalidCompletedAttemptCounts(int completedAttempts) {
        assertThatThrownBy(() -> policy.delayAfter(completedAttempts))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 101})
    void rejectsInvalidAttemptBudgets(int maxAttempts) {
        assertThatThrownBy(() -> new RetryPolicy(
                maxAttempts,
                Duration.ofSeconds(1),
                Duration.ofSeconds(2)
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInvalidDelaySettings() {
        assertThatThrownBy(() -> new RetryPolicy(
                5, null, Duration.ofSeconds(2)
        )).isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new RetryPolicy(
                5, Duration.ofSeconds(1), null
        )).isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new RetryPolicy(
                5, Duration.ZERO, Duration.ofSeconds(2)
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new RetryPolicy(
                5, Duration.ofSeconds(-1), Duration.ofSeconds(2)
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new RetryPolicy(
                5, Duration.ofSeconds(1), Duration.ZERO
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new RetryPolicy(
                5, Duration.ofSeconds(2), Duration.ofSeconds(1)
        )).isInstanceOf(IllegalArgumentException.class);
    }
}