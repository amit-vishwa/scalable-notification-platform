package io.github.amitvishwa.notification.application.idempotency;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RequestFingerprintTest {

    @Test
    void identicalContentProducesTheSameFingerprint() {
        String first = RequestFingerprint.calculate(
                "recipient@example.com",
                "Welcome",
                "Your account is ready."
        );

        String second = RequestFingerprint.calculate(
                "recipient@example.com",
                "Welcome",
                "Your account is ready."
        );

        assertThat(first)
                .isEqualTo(second)
                .matches("[0-9a-f]{64}");
    }

    @Test
    void changingAnyRequestFieldChangesTheFingerprint() {
        String original = RequestFingerprint.calculate(
                "recipient@example.com",
                "Welcome",
                "Your account is ready."
        );

        assertThat(RequestFingerprint.calculate(
                "other@example.com",
                "Welcome",
                "Your account is ready."
        )).isNotEqualTo(original);

        assertThat(RequestFingerprint.calculate(
                "recipient@example.com",
                "Different subject",
                "Your account is ready."
        )).isNotEqualTo(original);

        assertThat(RequestFingerprint.calculate(
                "recipient@example.com",
                "Welcome",
                "Different body"
        )).isNotEqualTo(original);
    }

    @Test
    void fieldBoundariesPreventAmbiguousConcatenation() {
        String first = RequestFingerprint.calculate(
                "recipient@example.com",
                "ab",
                "c"
        );

        String second = RequestFingerprint.calculate(
                "recipient@example.com",
                "a",
                "bc"
        );

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void preservesWhitespaceAndSupportsUnicode() {
        String original = RequestFingerprint.calculate(
                "recipient@example.com",
                "स्वागत",
                "Hello"
        );

        String withTrailingSpace = RequestFingerprint.calculate(
                "recipient@example.com",
                "स्वागत",
                "Hello "
        );

        assertThat(original).matches("[0-9a-f]{64}");
        assertThat(withTrailingSpace).isNotEqualTo(original);
    }
}