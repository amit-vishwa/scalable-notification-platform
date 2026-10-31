package io.github.amitvishwa.notification.domain.preference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PreferenceValuesTest {

    @Test
    void usesBothKeyValuesWithoutChangingTheirCase() {
        PreferenceKey first = new PreferenceKey(
                "employee-service",
                "Recipient@example.invalid"
        );
        PreferenceKey same = new PreferenceKey(
                "employee-service",
                "Recipient@example.invalid"
        );

        assertThat(first).isEqualTo(same);
        assertThat(first.hashCode()).isEqualTo(same.hashCode());

        assertThat(first).isNotEqualTo(new PreferenceKey(
                "billing-service",
                "Recipient@example.invalid"
        ));
        assertThat(first).isNotEqualTo(new PreferenceKey(
                "employee-service",
                "recipient@example.invalid"
        ));
    }

    @Test
    void rejectsInvalidKeyStructure() {
        assertThrows(
                NullPointerException.class,
                () -> new PreferenceKey(null, "a@example.invalid")
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new PreferenceKey(" ", "a@example.invalid")
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new PreferenceKey("app", "")
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new PreferenceKey(
                        "a".repeat(101),
                        "a@example.invalid"
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new PreferenceKey("app", "a".repeat(255))
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new PreferenceKey(
                        "app",
                        "recipient@example.invalid\r\n"
                )
        );
    }

    @Test
    void redactsRecipientFromKeyText() {
        PreferenceKey key = new PreferenceKey(
                "employee-service",
                "private-recipient@example.invalid"
        );

        assertThat(key.toString())
                .contains("recipientEmail=<redacted>")
                .doesNotContain("private-recipient@example.invalid");
    }

    @ParameterizedTest
    @CsvSource({
            "en-us, en-US",
            "hi-IN, hi-IN",
            "sr-latn-rs, sr-Latn-RS"
    })
    void storesCanonicalLocaleTag(String input, String expected) {
        EmailPreferenceSettings settings =
                new EmailPreferenceSettings(true, input, null);

        assertThat(settings.localeTag()).isEqualTo(expected);
        assertThat(settings.emailEnabled()).isTrue();
        assertThat(settings.quietHours()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            " ",
            "en_US",
            "en-US-!",
            "und",
            "x-private"
    })
    void rejectsMissingOrMalformedLanguageSelection(String input) {
        assertThrows(
                IllegalArgumentException.class,
                () -> new EmailPreferenceSettings(true, input, null)
        );
    }

    @Test
    void rejectsNullOrOversizedLocale() {
        assertThrows(
                NullPointerException.class,
                () -> new EmailPreferenceSettings(true, null, null)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new EmailPreferenceSettings(
                        true,
                        "a".repeat(64),
                        null
                )
        );
    }

    @Test
    void doesNotExposeMalformedLocaleInException() {
        String input = "en-US-!private-marker";

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> new EmailPreferenceSettings(true, input, null)
        );

        assertThat(failure.getMessage())
                .doesNotContain("private-marker");
        assertThat(failure.getCause()).isNull();
    }

    @Test
    void representsExplicitOptOutWithoutQuietHours() {
        EmailPreferenceSettings settings =
                new EmailPreferenceSettings(false, "en-IN", null);

        assertThat(settings.emailEnabled()).isFalse();
        assertThat(settings.quietHours()).isNull();
    }
}