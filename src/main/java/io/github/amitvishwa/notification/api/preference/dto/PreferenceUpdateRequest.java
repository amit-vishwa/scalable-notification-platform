package io.github.amitvishwa.notification.api.preference.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record PreferenceUpdateRequest(
        @NotNull
        @PositiveOrZero
        Long expectedVersion,

        @NotNull
        @Valid
        PreferenceSettingsRequest settings
) {

    @Override
    public String toString() {
        return "PreferenceUpdateRequest[expectedVersion="
                + expectedVersion + "]";
    }
}