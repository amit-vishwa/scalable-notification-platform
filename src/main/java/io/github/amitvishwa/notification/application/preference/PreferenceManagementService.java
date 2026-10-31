package io.github.amitvishwa.notification.application.preference;

import io.github.amitvishwa.notification.application.exception.PreferenceException;
import io.github.amitvishwa.notification.domain.preference.EmailPreference;
import io.github.amitvishwa.notification.domain.preference.EmailPreferenceSettings;
import io.github.amitvishwa.notification.domain.preference.PreferenceKey;
import io.github.amitvishwa.notification.infrastructure.persistence.preference.EmailPreferenceRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class PreferenceManagementService {

    private static final String UPDATE_PROCESS =
            "email-preference-api-update";

    private final EmailPreferenceRepository preferences;
    private final PreferenceCreationService creationService;
    private final Clock clock;

    public PreferenceManagementService(
            EmailPreferenceRepository preferences,
            PreferenceCreationService creationService,
            Clock clock
    ) {
        this.preferences = preferences;
        this.creationService = creationService;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public PreferenceResult create(
            PreferenceKey key,
            EmailPreferenceSettings settings
    ) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(settings, "settings must not be null");

        if (findExisting(key).isPresent()) {
            throw PreferenceException.alreadyExists();
        }

        try {
            return creationService.create(key, settings);
        } catch (DataIntegrityViolationException failure) {
            // The separate creation transaction has already rolled back.
            if (findExisting(key).isPresent()) {
                throw PreferenceException.alreadyExists();
            }

            // Do not misclassify unrelated database integrity failures.
            throw failure;
        }
    }

    @Transactional(readOnly = true)
    public PreferenceResult findById(
            UUID preferenceId,
            String sourceApplication
    ) {
        return PreferenceResult.from(
                requireOwned(preferenceId, sourceApplication)
        );
    }

    @Transactional(readOnly = true)
    public PreferenceResult lookup(PreferenceKey key) {
        Objects.requireNonNull(key, "key must not be null");

        EmailPreference preference = findExisting(key)
                .orElseThrow(PreferenceException::notFound);

        return PreferenceResult.from(preference);
    }

    @Transactional
    public PreferenceResult update(
            UUID preferenceId,
            String sourceApplication,
            long expectedVersion,
            EmailPreferenceSettings settings
    ) {
        Objects.requireNonNull(settings, "settings must not be null");

        // Resolve ownership before inspecting the stored version.
        EmailPreference preference =
                requireOwned(preferenceId, sourceApplication);

        if (expectedVersion < 0) {
            throw new IllegalArgumentException(
                    "expectedVersion must not be negative"
            );
        }

        long currentVersion = Objects.requireNonNull(
                preference.getVersion(),
                "Persisted preference version is missing"
        );

        if (currentVersion != expectedVersion) {
            throw PreferenceException.versionConflict();
        }

        preference.updateSettings(
                settings,
                UPDATE_PROCESS,
                clock.instant()
        );

        // @Version also detects a concurrent update after the initial read.
        preferences.flush();

        return PreferenceResult.from(preference);
    }

    private EmailPreference requireOwned(
            UUID preferenceId,
            String sourceApplication
    ) {
        Objects.requireNonNull(
                preferenceId,
                "preferenceId must not be null"
        );
        Objects.requireNonNull(
                sourceApplication,
                "sourceApplication must not be null"
        );

        return preferences.findOwnedById(
                preferenceId,
                sourceApplication
        ).orElseThrow(PreferenceException::notFound);
    }

    private Optional<EmailPreference> findExisting(PreferenceKey key) {
        return preferences.findByKey(
                key.sourceApplication(),
                key.recipientEmail()
        );
    }
}