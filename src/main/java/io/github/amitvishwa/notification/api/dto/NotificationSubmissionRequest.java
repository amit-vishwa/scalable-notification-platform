package io.github.amitvishwa.notification.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record NotificationSubmissionRequest(

        @NotBlank
        @Size(max = 100)
        String idempotencyKey,

        @NotBlank
        @Email
        @Size(max = 254)
        String recipientEmail,

        @NotBlank
        @Size(max = 200)
        String subject,

        @NotBlank
        @Size(max = 10_000)
        String body
) {
}