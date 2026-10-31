package io.github.amitvishwa.notification.api.preference.dto;

import java.time.Instant;
import java.util.UUID;

public record PreferenceResponse(
        UUID preferenceId,
        String sourceApplication,
        String recipientEmail,
        SettingsResponse settings,
        long version,
        String createdByProcess,
        String updatedByProcess,
        Instant createdAtTime,
        Instant updatedAtTime
) {

    public record SettingsResponse(
            boolean emailEnabled,
            String localeTag,
            QuietHoursResponse quietHours
    ) {
    }

    public record QuietHoursResponse(
            String start,
            String end,
            String timeZone
    ) {
    }

    @Override
    public String toString() {
        return "PreferenceResponse[preferenceId=" + preferenceId
                + ", version=" + version
                + ", recipientEmail=<redacted>]";
    }
}