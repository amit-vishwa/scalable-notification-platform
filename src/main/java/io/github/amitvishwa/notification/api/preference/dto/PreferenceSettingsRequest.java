package io.github.amitvishwa.notification.api.preference.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record PreferenceSettingsRequest(
        @NotNull
        Boolean emailEnabled,

        @NotBlank
        @Size(max = 63)
        String localeTag,

        @Valid
        QuietHoursRequest quietHours
) {

    public record QuietHoursRequest(
            @NotNull
            @Pattern(
                    regexp = "([01][0-9]|2[0-3]):[0-5][0-9]",
                    message = "must use HH:mm format"
            )
            String start,

            @NotNull
            @Pattern(
                    regexp = "([01][0-9]|2[0-3]):[0-5][0-9]",
                    message = "must use HH:mm format"
            )
            String end,

            @NotBlank
            @Size(max = 64)
            String timeZone
    ) {
    }
}