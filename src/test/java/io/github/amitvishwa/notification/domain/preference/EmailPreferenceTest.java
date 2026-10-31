package io.github.amitvishwa.notification.domain.preference;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EmailPreferenceTest {

    private static final Instant NOW =
            Instant.parse("2026-10-31T08:00:00Z");

    private static final PreferenceKey KEY = new PreferenceKey(
            "employee-service",
            "private-recipient@example.invalid"
    );

    @Test
    void createsAuditedPreferenceWithImmutableIdentity() {
        UUID id = UUID.randomUUID();
        EmailPreferenceSettings settings =
                new EmailPreferenceSettings(true, "en-IN", null);

        EmailPreference preference = EmailPreference.create(
                id, KEY, settings, "preference-api", NOW
        );

        assertThat(preference.getPreferenceId()).isEqualTo(id);
        assertThat(preference.getPreferenceKey()).isEqualTo(KEY);
        assertThat(preference.getSettings()).isEqualTo(settings);
        assertThat(preference.getCreatedByProcess())
                .isEqualTo("preference-api");
        assertThat(preference.getCreatedAtTime()).isEqualTo(NOW);
        assertThat(preference.getUpdatedAtTime()).isEqualTo(NOW);
        assertThat(preference.getVersion()).isNull();
    }

    @Test
    void updatesSettingsWithoutChangingIdentityOrCreationAudit() {
        EmailPreference preference = create();
        UUID id = preference.getPreferenceId();

        QuietHours hours = new QuietHours(
                LocalTime.of(22, 0),
                LocalTime.of(7, 0),
                ZoneId.of("Asia/Kolkata")
        );
        EmailPreferenceSettings replacement =
                new EmailPreferenceSettings(false, "hi-IN", hours);

        preference.updateSettings(
                replacement,
                "preference-update",
                NOW.plusSeconds(10)
        );

        assertThat(preference.getPreferenceId()).isEqualTo(id);
        assertThat(preference.getPreferenceKey()).isEqualTo(KEY);
        assertThat(preference.getSettings()).isEqualTo(replacement);
        assertThat(preference.getCreatedAtTime()).isEqualTo(NOW);
        assertThat(preference.getCreatedByProcess())
                .isEqualTo("preference-api");
        assertThat(preference.getUpdatedByProcess())
                .isEqualTo("preference-update");
        assertThat(preference.getUpdatedAtTime())
                .isEqualTo(NOW.plusSeconds(10));

        preference.updateSettings(
                new EmailPreferenceSettings(true, "en-IN", null),
                "preference-update",
                NOW.plusSeconds(20)
        );

        assertThat(preference.getSettings().quietHours()).isNull();
        assertThat(preference.getSettings().emailEnabled()).isTrue();
    }

    @Test
    void rejectsInvalidUpdateWithoutChangingSettings() {
        EmailPreference preference = create();
        EmailPreferenceSettings original = preference.getSettings();

        assertThrows(
                IllegalArgumentException.class,
                () -> preference.updateSettings(
                        new EmailPreferenceSettings(false, "hi-IN", null),
                        " ",
                        NOW.plusSeconds(10)
                )
        );

        assertThat(preference.getSettings()).isEqualTo(original);
        assertThat(preference.getUpdatedAtTime()).isEqualTo(NOW);
    }

    @Test
    void redactsRecipientFromEntityText() {
        assertThat(create().toString())
                .contains("recipientEmail=<redacted>")
                .doesNotContain("private-recipient@example.invalid");
    }

    private static EmailPreference create() {
        return EmailPreference.create(
                UUID.randomUUID(),
                KEY,
                new EmailPreferenceSettings(true, "en-IN", null),
                "preference-api",
                NOW
        );
    }
}