package io.github.amitvishwa.notification.api.preference;

import io.github.amitvishwa.notification.api.error.InvalidPreferenceRequestException;
import io.github.amitvishwa.notification.api.preference.dto.PreferenceResponse;
import io.github.amitvishwa.notification.api.preference.dto.PreferenceSettingsRequest;
import io.github.amitvishwa.notification.application.preference.PreferenceResult;
import io.github.amitvishwa.notification.domain.preference.EmailPreferenceSettings;
import io.github.amitvishwa.notification.domain.preference.PreferenceKey;
import io.github.amitvishwa.notification.domain.preference.QuietHours;

import java.time.DateTimeException;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.Locale;

final class PreferenceApiMapper {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
                    .withResolverStyle(ResolverStyle.STRICT);

    private PreferenceApiMapper() {
    }

    static PreferenceKey toKey(
            String sourceApplication,
            String recipientEmail
    ) {
        if (sourceApplication == null || recipientEmail == null) {
            throw new InvalidPreferenceRequestException();
        }

        try {
            return new PreferenceKey(
                    sourceApplication,
                    recipientEmail
            );
        } catch (IllegalArgumentException failure) {
            throw new InvalidPreferenceRequestException();
        }
    }

    static EmailPreferenceSettings toSettings(
            PreferenceSettingsRequest request
    ) {
        if (request == null
                || request.emailEnabled() == null
                || request.localeTag() == null) {
            throw new InvalidPreferenceRequestException();
        }

        PreferenceSettingsRequest.QuietHoursRequest hours =
                request.quietHours();

        if (hours != null
                && (hours.start() == null
                || hours.end() == null
                || hours.timeZone() == null)) {
            throw new InvalidPreferenceRequestException();
        }

        try {
            QuietHours quietHours = hours == null
                    ? null
                    : new QuietHours(
                            LocalTime.parse(hours.start(), TIME_FORMAT),
                            LocalTime.parse(hours.end(), TIME_FORMAT),
                            ZoneId.of(hours.timeZone())
                    );

            return new EmailPreferenceSettings(
                    request.emailEnabled(),
                    request.localeTag(),
                    quietHours
            );
        } catch (IllegalArgumentException | DateTimeException failure) {
            // Do not retain a cause containing submitted input.
            throw new InvalidPreferenceRequestException();
        }
    }

    static PreferenceResponse toResponse(PreferenceResult result) {
        EmailPreferenceSettings settings = result.settings();
        QuietHours hours = settings.quietHours();

        PreferenceResponse.QuietHoursResponse quietHours = hours == null
                ? null
                : new PreferenceResponse.QuietHoursResponse(
                        TIME_FORMAT.format(hours.start()),
                        TIME_FORMAT.format(hours.end()),
                        hours.zone().getId()
                );

        return new PreferenceResponse(
                result.preferenceId(),
                result.sourceApplication(),
                result.recipientEmail(),
                new PreferenceResponse.SettingsResponse(
                        settings.emailEnabled(),
                        settings.localeTag(),
                        quietHours
                ),
                result.version(),
                result.createdByProcess(),
                result.updatedByProcess(),
                result.createdAtTime(),
                result.updatedAtTime()
        );
    }
}