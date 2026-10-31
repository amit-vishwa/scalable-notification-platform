package io.github.amitvishwa.notification.application.preference;

import io.github.amitvishwa.notification.domain.preference.EmailPreference;
import io.github.amitvishwa.notification.domain.preference.EmailPreferenceSettings;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record PreferenceResult(
        UUID preferenceId,
        String sourceApplication,
        String recipientEmail,
        EmailPreferenceSettings settings,
        long version,
        String createdByProcess,
        String updatedByProcess,
        Instant createdAtTime,
        Instant updatedAtTime
) {

    static PreferenceResult from(EmailPreference preference) {
        Objects.requireNonNull(
                preference,
                "preference must not be null"
        );

        long persistedVersion = Objects.requireNonNull(
                preference.getVersion(),
                "Persisted preference version is missing"
        );

        return new PreferenceResult(
                preference.getPreferenceId(),
                preference.getSourceApplication(),
                preference.getRecipientEmail(),
                preference.getSettings(),
                persistedVersion,
                preference.getCreatedByProcess(),
                preference.getUpdatedByProcess(),
                preference.getCreatedAtTime(),
                preference.getUpdatedAtTime()
        );
    }

    @Override
    public String toString() {
        return "PreferenceResult[preferenceId=" + preferenceId
                + ", version=" + version
                + ", recipientEmail=<redacted>]";
    }
}