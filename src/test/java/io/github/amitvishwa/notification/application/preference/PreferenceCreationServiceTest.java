package io.github.amitvishwa.notification.application.preference;

import io.github.amitvishwa.notification.domain.preference.EmailPreference;
import io.github.amitvishwa.notification.domain.preference.EmailPreferenceSettings;
import io.github.amitvishwa.notification.domain.preference.PreferenceKey;
import io.github.amitvishwa.notification.infrastructure.persistence.preference.EmailPreferenceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PreferenceCreationServiceTest {

    private static final Instant NOW =
            Instant.parse("2026-10-31T10:00:00Z");

    private static final PreferenceKey KEY = new PreferenceKey(
            "employee-service",
            "Recipient@example.invalid"
    );

    private static final EmailPreferenceSettings SETTINGS =
            new EmailPreferenceSettings(true, "en-IN", null);

    @Mock
    private EmailPreferenceRepository preferences;

    private PreferenceCreationService service;

    @BeforeEach
    void setUp() {
        service = new PreferenceCreationService(
                preferences,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void createsPreferenceAndReturnsAnImmutablePersistedSnapshot() {
        when(preferences.saveAndFlush(any(EmailPreference.class)))
                .thenAnswer(invocation -> {
                    EmailPreference preference = invocation.getArgument(
                            0,
                            EmailPreference.class
                    );

                    // Unit fixture: Hibernate assigns this during persistence.
                    ReflectionTestUtils.setField(
                            preference,
                            "version",
                            0L
                    );

                    return preference;
                });

        PreferenceResult result = service.create(KEY, SETTINGS);

        ArgumentCaptor<EmailPreference> captor =
                ArgumentCaptor.forClass(EmailPreference.class);

        verify(preferences).saveAndFlush(captor.capture());

        EmailPreference saved = captor.getValue();

        assertThat(saved.getPreferenceId()).isNotNull();
        assertThat(saved.getPreferenceKey()).isEqualTo(KEY);
        assertThat(saved.getSettings()).isEqualTo(SETTINGS);

        assertThat(result.preferenceId())
                .isEqualTo(saved.getPreferenceId());
        assertThat(result.sourceApplication())
                .isEqualTo(KEY.sourceApplication());
        assertThat(result.recipientEmail())
                .isEqualTo(KEY.recipientEmail());
        assertThat(result.settings()).isEqualTo(SETTINGS);
        assertThat(result.version()).isZero();

        assertThat(result.createdByProcess())
                .isEqualTo("email-preference-api-create");
        assertThat(result.updatedByProcess())
                .isEqualTo("email-preference-api-create");
        assertThat(result.createdAtTime()).isEqualTo(NOW);
        assertThat(result.updatedAtTime()).isEqualTo(NOW);

        assertThat(result.toString())
                .doesNotContain(KEY.recipientEmail())
                .contains("recipientEmail=<redacted>");

        saved.updateSettings(
                new EmailPreferenceSettings(false, "hi-IN", null),
                "later-update",
                NOW.plusSeconds(1)
        );

        assertThat(result.settings()).isEqualTo(SETTINGS);
        assertThat(result.updatedAtTime()).isEqualTo(NOW);
    }

    @Test
    void propagatesPersistenceFailure() {
        DataIntegrityViolationException failure =
                new DataIntegrityViolationException(
                        "Simulated integrity failure"
                );

        when(preferences.saveAndFlush(any(EmailPreference.class)))
                .thenThrow(failure);

        assertThatThrownBy(() -> service.create(KEY, SETTINGS))
                .isSameAs(failure);
    }

    @Test
    void refusesToInventAVersionForAnUnpersistedEntity() {
        when(preferences.saveAndFlush(any(EmailPreference.class)))
                .thenAnswer(invocation ->
                        invocation.getArgument(0, EmailPreference.class)
                );

        assertThatThrownBy(() -> service.create(KEY, SETTINGS))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("Persisted preference version is missing");
    }
}