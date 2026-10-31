package io.github.amitvishwa.notification.domain.preference;

import java.util.IllformedLocaleException;
import java.util.Locale;
import java.util.Objects;

public record EmailPreferenceSettings(
        boolean emailEnabled,
        String localeTag,
        QuietHours quietHours
) {

    private static final int MAX_LOCALE_LENGTH = 63;

    public EmailPreferenceSettings {
        Objects.requireNonNull(
                localeTag,
                "localeTag must not be null"
        );

        if (localeTag.isBlank()
                || localeTag.length() > MAX_LOCALE_LENGTH) {
            throw invalidLocale();
        }

        Locale parsed;

        try {
            parsed = new Locale.Builder()
                    .setLanguageTag(localeTag)
                    .build();
        } catch (IllformedLocaleException exception) {
            // Do not retain a cause containing raw request input.
            throw invalidLocale();
        }

        if (parsed.getLanguage().isEmpty()
                || "und".equals(parsed.getLanguage())) {
            throw invalidLocale();
        }

        localeTag = parsed.toLanguageTag();

        if (localeTag.length() > MAX_LOCALE_LENGTH) {
            throw invalidLocale();
        }
    }

    private static IllegalArgumentException invalidLocale() {
        return new IllegalArgumentException(
                "localeTag must identify a language using "
                        + "a well-formed tag of at most 63 characters"
        );
    }
}