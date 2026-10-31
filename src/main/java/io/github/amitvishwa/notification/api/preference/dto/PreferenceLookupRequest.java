package io.github.amitvishwa.notification.api.preference.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PreferenceLookupRequest(
        @NotBlank
        @Email
        @Size(max = 254)
        String recipientEmail
) {

    @Override
    public String toString() {
        return "PreferenceLookupRequest[recipientEmail=<redacted>]";
    }
}