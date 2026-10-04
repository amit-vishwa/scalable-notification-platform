package io.github.amitvishwa.notification.application.port.email;

import io.github.amitvishwa.notification.domain.model.DeliveryAttemptOutcome;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmailProviderContractTest {

    @Test
    void acceptsSuccessfulResultsWithOrWithoutProviderMessageId() {
        EmailProviderResult withId =
                EmailProviderResult.accepted("provider-message-123");

        assertThat(withId.outcome())
                .isEqualTo(DeliveryAttemptOutcome.ACCEPTED);
        assertThat(withId.providerMessageId())
                .isEqualTo("provider-message-123");
        assertThat(withId.failureCode()).isNull();

        EmailProviderResult withoutId = EmailProviderResult.accepted(null);

        assertThat(withoutId.outcome())
                .isEqualTo(DeliveryAttemptOutcome.ACCEPTED);
        assertThat(withoutId.providerMessageId()).isNull();
    }

    @Test
    void representsTemporaryAndPermanentFailuresWithoutProviderMessageId() {
        EmailProviderResult temporary =
                EmailProviderResult.temporaryFailure("PROVIDER_UNAVAILABLE");
        EmailProviderResult permanent =
                EmailProviderResult.permanentFailure("INVALID_RECIPIENT");

        assertThat(temporary.outcome())
                .isEqualTo(DeliveryAttemptOutcome.TEMPORARY_FAILURE);
        assertThat(temporary.failureCode())
                .isEqualTo("PROVIDER_UNAVAILABLE");
        assertThat(temporary.providerMessageId()).isNull();

        assertThat(permanent.outcome())
                .isEqualTo(DeliveryAttemptOutcome.PERMANENT_FAILURE);
        assertThat(permanent.failureCode())
                .isEqualTo("INVALID_RECIPIENT");
        assertThat(permanent.providerMessageId()).isNull();
    }

    @Test
    void rejectsInconsistentResults() {
        assertThatThrownBy(() -> new EmailProviderResult(
                null, null, null
        )).isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new EmailProviderResult(
                DeliveryAttemptOutcome.ACCEPTED,
                "provider-message-123",
                "UNEXPECTED_FAILURE"
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new EmailProviderResult(
                DeliveryAttemptOutcome.TEMPORARY_FAILURE,
                "provider-message-123",
                "PROVIDER_UNAVAILABLE"
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(
                () -> EmailProviderResult.permanentFailure(null)
        ).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validatesProviderMessageIdLength() {
        for (String invalidId : List.of("", " ", "x".repeat(256))) {
            assertThatThrownBy(
                    () -> EmailProviderResult.accepted(invalidId)
            ).isInstanceOf(IllegalArgumentException.class);
        }

        assertThat(
                EmailProviderResult.accepted("x".repeat(255))
                        .providerMessageId()
        ).hasSize(255);
    }

    @Test
    void rejectsUnsafeOrOversizedFailureCodes() {
        for (String invalidCode : List.of(
                "",
                " ",
                "lowercase",
                "CODE-WITH-HYPHENS",
                "recipient@example.com",
                "X".repeat(65)
        )) {
            assertThatThrownBy(
                    () -> EmailProviderResult.temporaryFailure(invalidCode)
            ).isInstanceOf(IllegalArgumentException.class);
        }

        assertThat(
                EmailProviderResult.temporaryFailure("X".repeat(64))
                        .failureCode()
        ).hasSize(64);
    }

    @Test
    void excludesPayloadAndProviderMessageIdFromStringRepresentations() {
        UUID notificationId = UUID.randomUUID();

        EmailMessage message = new EmailMessage(
                notificationId,
                "private-recipient@example.com",
                "Private subject",
                "Private email body"
        );

        assertThat(message.toString())
                .contains(notificationId.toString())
                .doesNotContain(
                        "private-recipient@example.com",
                        "Private subject",
                        "Private email body"
                );

        assertThat(
                EmailProviderResult.accepted("private-provider-id")
                        .toString()
        ).doesNotContain("private-provider-id");
    }

    @Test
    void rejectsInvalidEmailMessageFields() {
        UUID notificationId = UUID.randomUUID();

        assertThatThrownBy(() -> new EmailMessage(
                null, "recipient@example.com", "Subject", "Body"
        )).isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new EmailMessage(
                notificationId, " ", "Subject", "Body"
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new EmailMessage(
                notificationId, "recipient@example.com", " ", "Body"
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new EmailMessage(
                notificationId, "recipient@example.com", "Subject", " "
        )).isInstanceOf(IllegalArgumentException.class);
    }
}