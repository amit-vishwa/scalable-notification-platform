package io.github.amitvishwa.notification.application.preference;

import io.github.amitvishwa.notification.domain.model.SuppressionReason;
import io.github.amitvishwa.notification.domain.preference.EmailPreference;
import io.github.amitvishwa.notification.domain.preference.EmailPreferenceSettings;
import io.github.amitvishwa.notification.domain.preference.PreferenceKey;
import io.github.amitvishwa.notification.domain.preference.QuietHours;
import io.github.amitvishwa.notification.infrastructure.persistence.preference.EmailPreferenceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

@Service
public class PreferenceEvaluationService {

    private final EmailPreferenceRepository preferences;

    public PreferenceEvaluationService(
            EmailPreferenceRepository preferences
    ) {
        this.preferences = Objects.requireNonNull(
                preferences,
                "preferences must not be null"
        );
    }

    /**
     * Evaluates the preference snapshot inside the existing claim transaction.
     * Lookup or settings failures propagate; they never authorize delivery.
     */
    @Transactional(
            propagation = Propagation.MANDATORY,
            readOnly = true
    )
    public PreferenceDecision evaluate(
            PreferenceKey key,
            Instant now
    ) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(now, "now must not be null");

        Optional<EmailPreference> preference = preferences.findByKey(
                key.sourceApplication(),
                key.recipientEmail()
        );

        if (preference.isEmpty()) {
            return new PreferenceDecision.Suppressed(
                    SuppressionReason.PREFERENCE_MISSING
            );
        }

        EmailPreferenceSettings settings =
                preference.orElseThrow().getSettings();

        if (!settings.emailEnabled()) {
            return new PreferenceDecision.Suppressed(
                    SuppressionReason.EMAIL_DISABLED
            );
        }

        QuietHours quietHours = settings.quietHours();

        if (quietHours == null) {
            return PreferenceDecision.Allowed.INSTANCE;
        }

        Instant nextEligibleAt = quietHours.nextEligibleAt(now);

        if (nextEligibleAt.equals(now)) {
            return PreferenceDecision.Allowed.INSTANCE;
        }

        if (nextEligibleAt.isBefore(now)) {
            throw new IllegalStateException(
                    "Preference evaluation returned an invalid delivery time"
            );
        }

        return new PreferenceDecision.Deferred(nextEligibleAt);
    }
}