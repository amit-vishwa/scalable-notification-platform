package io.github.amitvishwa.notification.application.preference;

import io.github.amitvishwa.notification.domain.model.SuppressionReason;
import io.github.amitvishwa.notification.domain.preference.EmailPreference;
import io.github.amitvishwa.notification.domain.preference.EmailPreferenceSettings;
import io.github.amitvishwa.notification.domain.preference.PreferenceKey;
import io.github.amitvishwa.notification.domain.preference.QuietHours;
import io.github.amitvishwa.notification.infrastructure.persistence.preference.EmailPreferenceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PreferenceEvaluationServiceTest {

    private static final Instant NOW =
            Instant.parse("2026-10-31T23:00:00Z");

    private static final PreferenceKey KEY = new PreferenceKey(
            "employee-service",
            "Recipient@example.invalid"
    );

    @Mock
    private EmailPreferenceRepository preferences;

    private PreferenceEvaluationService service;

    @BeforeEach
    void setUp() {
        service = new PreferenceEvaluationService(preferences);
    }

    @Test
    void suppressesWhenPreferenceIsMissing() {
        when(preferences.findByKey(
                KEY.sourceApplication(),
                KEY.recipientEmail()
        )).thenReturn(Optional.empty());

        PreferenceDecision decision = service.evaluate(KEY, NOW);

        assertThat(decision).isEqualTo(
                new PreferenceDecision.Suppressed(
                        SuppressionReason.PREFERENCE_MISSING
                )
        );

        verify(preferences).findByKey(
                "employee-service",
                "Recipient@example.invalid"
        );
    }

    @Test
    void disabledPreferenceTakesPrecedenceOverQuietHours() {
        givenSettings(new EmailPreferenceSettings(
                false,
                "en-IN",
                overnightQuietHours()
        ));

        assertThat(service.evaluate(KEY, NOW)).isEqualTo(
                new PreferenceDecision.Suppressed(
                        SuppressionReason.EMAIL_DISABLED
                )
        );
    }

    @Test
    void allowsEnabledPreferenceWithoutQuietHours() {
        givenSettings(new EmailPreferenceSettings(
                true,
                "en-IN",
                null
        ));

        assertThat(service.evaluate(KEY, NOW))
                .isSameAs(PreferenceDecision.Allowed.INSTANCE);
    }

    @ParameterizedTest
    @CsvSource({
            "2026-10-31T21:59:59Z,",
            "2026-10-31T22:00:00Z,2026-11-01T07:00:00Z",
            "2026-11-01T06:59:59Z,2026-11-01T07:00:00Z",
            "2026-11-01T07:00:00Z,",
            "2026-11-01T12:00:00Z,"
    })
    void respectsQuietHoursBoundaries(
            String evaluatedAt,
            String expectedNextEligibleAt
    ) {
        givenSettings(new EmailPreferenceSettings(
                true,
                "en-IN",
                overnightQuietHours()
        ));

        PreferenceDecision decision = service.evaluate(
                KEY,
                Instant.parse(evaluatedAt)
        );

        if (expectedNextEligibleAt == null) {
            assertThat(decision)
                    .isSameAs(PreferenceDecision.Allowed.INSTANCE);
        } else {
            assertThat(decision).isEqualTo(
                    new PreferenceDecision.Deferred(
                            Instant.parse(expectedNextEligibleAt)
                    )
            );
        }
    }

    @Test
    void propagatesDatabaseFailureInsteadOfAllowingDelivery() {
        DataAccessResourceFailureException failure =
                new DataAccessResourceFailureException(
                        "Simulated database failure"
                );

        when(preferences.findByKey(
                KEY.sourceApplication(),
                KEY.recipientEmail()
        )).thenThrow(failure);

        assertThatThrownBy(() -> service.evaluate(KEY, NOW))
                .isSameAs(failure);
    }

    @Test
    void propagatesCorruptedSettingsInsteadOfAllowingDelivery() {
        EmailPreference preference = mock(EmailPreference.class);

        IllegalStateException failure =
                new IllegalStateException(
                        "Invalid persisted preference"
                );

        when(preferences.findByKey(
                KEY.sourceApplication(),
                KEY.recipientEmail()
        )).thenReturn(Optional.of(preference));

        when(preference.getSettings()).thenThrow(failure);

        assertThatThrownBy(() -> service.evaluate(KEY, NOW))
                .isSameAs(failure);
    }

    @Test
    void rejectsNullInputsBeforeQueryingDatabase() {
        assertThrows(
                NullPointerException.class,
                () -> service.evaluate(null, NOW)
        );

        assertThrows(
                NullPointerException.class,
                () -> service.evaluate(KEY, null)
        );

        verifyNoInteractions(preferences);
    }

    @Test
    void rejectsIncompleteDecisions() {
        assertThrows(
                NullPointerException.class,
                () -> new PreferenceDecision.Suppressed(null)
        );

        assertThrows(
                NullPointerException.class,
                () -> new PreferenceDecision.Deferred(null)
        );
    }

    private void givenSettings(EmailPreferenceSettings settings) {
        EmailPreference preference = EmailPreference.create(
                UUID.randomUUID(),
                KEY,
                settings,
                "preference-unit-test",
                NOW
        );

        when(preferences.findByKey(
                KEY.sourceApplication(),
                KEY.recipientEmail()
        )).thenReturn(Optional.of(preference));
    }

    private static QuietHours overnightQuietHours() {
        return new QuietHours(
                LocalTime.of(22, 0),
                LocalTime.of(7, 0),
                ZoneId.of("UTC")
        );
    }
}