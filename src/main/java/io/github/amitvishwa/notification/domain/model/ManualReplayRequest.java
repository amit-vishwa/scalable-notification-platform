package io.github.amitvishwa.notification.domain.model;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public record ManualReplayRequest(
        UUID commandId,
        UUID notificationId,
        String sourceApplication,
        long expectedVersion,
        String operatorId,
        String investigationReference,
        boolean duplicateRiskAcknowledged,
        boolean previousSenderStopped
) {

    private static final Pattern REFERENCE_PATTERN =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,99}");

    public ManualReplayRequest {
        Objects.requireNonNull(commandId, "commandId must not be null");
        Objects.requireNonNull(
                notificationId,
                "notificationId must not be null"
        );

        requireSingleLine(sourceApplication, "sourceApplication");
        requireSingleLine(operatorId, "operatorId");

        Objects.requireNonNull(
                investigationReference,
                "investigationReference must not be null"
        );

        if (!REFERENCE_PATTERN.matcher(investigationReference).matches()) {
            throw new IllegalArgumentException(
                    "investigationReference must be a safe reference "
                            + "containing 1 to 100 characters"
            );
        }

        if (expectedVersion < 0 || expectedVersion == Long.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "expectedVersion is outside the supported range"
            );
        }

        if (!duplicateRiskAcknowledged) {
            throw new IllegalArgumentException(
                    "Explicit duplicate-risk acknowledgement is required"
            );
        }

        if (!previousSenderStopped) {
            throw new IllegalArgumentException(
                    "Confirmation that the previous sender is stopped "
                            + "is required"
            );
        }
    }

    private static void requireSingleLine(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");

        if (value.isBlank()
                || value.length() > 100
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                    field + " must contain 1 to 100 non-control characters"
            );
        }
    }
}