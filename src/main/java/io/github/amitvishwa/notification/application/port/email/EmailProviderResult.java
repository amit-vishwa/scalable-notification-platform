package io.github.amitvishwa.notification.application.port.email;

import io.github.amitvishwa.notification.domain.model.DeliveryAttemptOutcome;

import java.util.Objects;
import java.util.regex.Pattern;

public record EmailProviderResult(
        DeliveryAttemptOutcome outcome,
        String providerMessageId,
        String failureCode
) {

    private static final int MAX_PROVIDER_MESSAGE_ID_LENGTH = 255;

    private static final Pattern SAFE_FAILURE_CODE =
            Pattern.compile("[A-Z0-9_]{1,64}");

    public EmailProviderResult {
        Objects.requireNonNull(outcome, "outcome must not be null");

        if (outcome == DeliveryAttemptOutcome.ACCEPTED) {
            if (failureCode != null) {
                throw new IllegalArgumentException(
                        "Accepted results must not contain a failure code"
                );
            }

            if (providerMessageId != null
                    && (providerMessageId.isBlank()
                    || providerMessageId.length()
                    > MAX_PROVIDER_MESSAGE_ID_LENGTH)) {
                throw new IllegalArgumentException(
                        "providerMessageId must contain 1 to 255 characters"
                );
            }
        } else {
            if (providerMessageId != null) {
                throw new IllegalArgumentException(
                        "Failure results must not contain a provider message ID"
                );
            }

            if (failureCode == null
                    || !SAFE_FAILURE_CODE.matcher(failureCode).matches()) {
                throw new IllegalArgumentException(
                        "failureCode must contain 1 to 64 uppercase "
                                + "letters, digits or underscores"
                );
            }
        }
    }

    public static EmailProviderResult accepted(
            String providerMessageId
    ) {
        return new EmailProviderResult(
                DeliveryAttemptOutcome.ACCEPTED,
                providerMessageId,
                null
        );
    }

    public static EmailProviderResult temporaryFailure(
            String failureCode
    ) {
        return new EmailProviderResult(
                DeliveryAttemptOutcome.TEMPORARY_FAILURE,
                null,
                failureCode
        );
    }

    public static EmailProviderResult permanentFailure(
            String failureCode
    ) {
        return new EmailProviderResult(
                DeliveryAttemptOutcome.PERMANENT_FAILURE,
                null,
                failureCode
        );
    }

    @Override
    public String toString() {
        return "EmailProviderResult[outcome=" + outcome
                + ", failureCode=" + failureCode + "]";
    }
}