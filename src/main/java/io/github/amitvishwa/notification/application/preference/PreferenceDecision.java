package io.github.amitvishwa.notification.application.preference;

import io.github.amitvishwa.notification.domain.model.SuppressionReason;

import java.time.Instant;
import java.util.Objects;

public sealed interface PreferenceDecision
        permits PreferenceDecision.Allowed,
                PreferenceDecision.Suppressed,
                PreferenceDecision.Deferred {

    enum Allowed implements PreferenceDecision {
        INSTANCE
    }

    record Suppressed(
            SuppressionReason reason
    ) implements PreferenceDecision {

        public Suppressed {
            Objects.requireNonNull(reason, "reason must not be null");
        }
    }

    record Deferred(
            Instant nextEligibleAt
    ) implements PreferenceDecision {

        public Deferred {
            Objects.requireNonNull(
                    nextEligibleAt,
                    "nextEligibleAt must not be null"
            );
        }
    }
}