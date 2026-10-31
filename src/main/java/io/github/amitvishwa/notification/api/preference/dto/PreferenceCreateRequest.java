package io.github.amitvishwa.notification.api.preference.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record PreferenceCreateRequest(
        @NotBlank
        @Email
        @Size(max = 254)
        String recipientEmail,

        @NotNull
        @Valid
        PreferenceSettingsRequest settings
) {

    @Override
    public String toString() {
        return "PreferenceCreateRequest[recipientEmail=<redacted>]";
    }
}