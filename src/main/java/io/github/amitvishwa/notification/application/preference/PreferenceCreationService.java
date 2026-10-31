package io.github.amitvishwa.notification.application.preference;

import io.github.amitvishwa.notification.domain.preference.EmailPreference;
import io.github.amitvishwa.notification.domain.preference.EmailPreferenceSettings;
import io.github.amitvishwa.notification.domain.preference.PreferenceKey;
import io.github.amitvishwa.notification.infrastructure.persistence.preference.EmailPreferenceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

@Service
public class PreferenceCreationService {

    private static final String PROCESS = "email-preference-api-create";

    private final EmailPreferenceRepository preferences;
    private final Clock clock;

    public PreferenceCreationService(
            EmailPreferenceRepository preferences,
            Clock clock
    ) {
        this.preferences = preferences;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PreferenceResult create(
            PreferenceKey key,
            EmailPreferenceSettings settings
    ) {
        EmailPreference preference = EmailPreference.create(
                UUID.randomUUID(),
                key,
                settings,
                PROCESS,
                clock.instant()
        );

        EmailPreference saved = preferences.saveAndFlush(preference);

        return PreferenceResult.from(saved);
    }
}