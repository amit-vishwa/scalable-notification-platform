package io.github.amitvishwa.notification.api.preference;

import io.github.amitvishwa.notification.api.error.InvalidPreferenceRequestException;
import io.github.amitvishwa.notification.api.preference.dto.PreferenceCreateRequest;
import io.github.amitvishwa.notification.api.preference.dto.PreferenceLookupRequest;
import io.github.amitvishwa.notification.api.preference.dto.PreferenceResponse;
import io.github.amitvishwa.notification.api.preference.dto.PreferenceSettingsRequest;
import io.github.amitvishwa.notification.api.preference.dto.PreferenceUpdateRequest;
import io.github.amitvishwa.notification.application.preference.PreferenceResult;
import io.github.amitvishwa.notification.domain.preference.EmailPreferenceSettings;
import io.github.amitvishwa.notification.domain.preference.PreferenceKey;
import io.github.amitvishwa.notification.domain.preference.QuietHours;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PreferenceApiContractTest {

    private static final String RECIPIENT = "Recipient@example.invalid";

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void createValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        if (validatorFactory != null) {
            validatorFactory.close();
        }
    }

    @Test
    void validatesAndMapsAnOvernightPreferenceWithoutNormalizingRecipient() {
        PreferenceCreateRequest request = new PreferenceCreateRequest(
                RECIPIENT,
                new PreferenceSettingsRequest(
                        true,
                        "en-us",
                        new PreferenceSettingsRequest.QuietHoursRequest(
                                "22:00",
                                "07:00",
                                "Asia/Kolkata"
                        )
                )
        );

        assertThat(invalidFields(request)).isEmpty();

        PreferenceKey key = PreferenceApiMapper.toKey(
                "employee-service",
                request.recipientEmail()
        );

        EmailPreferenceSettings settings =
                PreferenceApiMapper.toSettings(request.settings());

        assertThat(key.recipientEmail()).isEqualTo(RECIPIENT);
        assertThat(settings.emailEnabled()).isTrue();
        assertThat(settings.localeTag()).isEqualTo("en-US");
        assertThat(settings.quietHours()).isEqualTo(new QuietHours(
                LocalTime.of(22, 0),
                LocalTime.of(7, 0),
                ZoneId.of("Asia/Kolkata")
        ));
    }

    @Test
    void requiresSettingsAndAnExplicitEnabledValue() {
        assertThat(invalidFields(new PreferenceCreateRequest(
                RECIPIENT,
                null
        ))).contains("settings");

        assertThat(invalidFields(new PreferenceCreateRequest(
                RECIPIENT,
                new PreferenceSettingsRequest(null, "en-IN", null)
        ))).contains("settings.emailEnabled");
    }

    @Test
    void acceptsExplicitFalseAndAbsentQuietHours() {
        PreferenceSettingsRequest settings =
                new PreferenceSettingsRequest(false, "en-IN", null);

        assertThat(invalidFields(new PreferenceCreateRequest(
                RECIPIENT,
                settings
        ))).isEmpty();

        EmailPreferenceSettings mapped =
                PreferenceApiMapper.toSettings(settings);

        assertThat(mapped.emailEnabled()).isFalse();
        assertThat(mapped.quietHours()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-an-email", " ", "recipient@"})
    void rejectsInvalidRecipient(String recipient) {
        assertThat(invalidFields(new PreferenceCreateRequest(
                recipient,
                validSettings()
        ))).contains("recipientEmail");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {-1})
    void requiresANonnegativeExpectedVersion(Long version) {
        assertThat(invalidFields(new PreferenceUpdateRequest(
                version,
                validSettings()
        ))).contains("expectedVersion");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "7:00",
            "24:00",
            "22:00:00",
            "22:00:00.000001",
            "-01:00"
    })
    void rejectsTimesOutsideStrictMinuteFormat(String start) {
        PreferenceSettingsRequest settings =
                settingsWithHours(start, "07:00", "Asia/Kolkata");

        assertThat(invalidFields(new PreferenceCreateRequest(
                RECIPIENT,
                settings
        ))).contains("settings.quietHours.start");

        assertThrows(
                InvalidPreferenceRequestException.class,
                () -> PreferenceApiMapper.toSettings(settings)
        );
    }

    @Test
    void rejectsPartialQuietHours() {
        PreferenceSettingsRequest settings =
                settingsWithHours(null, "07:00", null);

        assertThat(invalidFields(new PreferenceCreateRequest(
                RECIPIENT,
                settings
        ))).contains(
                "settings.quietHours.start",
                "settings.quietHours.timeZone"
        );

        assertThrows(
                InvalidPreferenceRequestException.class,
                () -> PreferenceApiMapper.toSettings(settings)
        );
    }

    @Test
    void rejectsEqualQuietHoursEndpoints() {
        assertThrows(
                InvalidPreferenceRequestException.class,
                () -> PreferenceApiMapper.toSettings(
                        settingsWithHours(
                                "22:00",
                                "22:00",
                                "Asia/Kolkata"
                        )
                )
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"Not/AZone", "+02:00"})
    void rejectsUnsupportedZonesWithoutRetainingRawInput(String zone) {
        InvalidPreferenceRequestException failure = assertThrows(
                InvalidPreferenceRequestException.class,
                () -> PreferenceApiMapper.toSettings(
                        settingsWithHours("22:00", "07:00", zone)
                )
        );

        assertThat(failure.getCause()).isNull();
        assertThat(failure.toString()).doesNotContain(zone);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not_a_locale", "und", "x-private", " "})
    void rejectsInvalidLocaleWithoutRetainingItsCause(String locale) {
        InvalidPreferenceRequestException failure = assertThrows(
                InvalidPreferenceRequestException.class,
                () -> PreferenceApiMapper.toSettings(
                        new PreferenceSettingsRequest(
                                true,
                                locale,
                                null
                        )
                )
        );

        assertThat(failure.getCause()).isNull();
        assertThat(failure.getMessage()).isEqualTo(
                "The preference request contains invalid values."
        );
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void mapsResponseSettingsAndRedactsRecipient(boolean includeQuietHours) {
        QuietHours hours = includeQuietHours
                ? new QuietHours(
                        LocalTime.of(7, 0),
                        LocalTime.of(9, 0),
                        ZoneId.of("UTC")
                )
                : null;

        Instant now = Instant.parse("2026-10-31T10:00:00Z");
        UUID id = UUID.randomUUID();

        PreferenceResult result = new PreferenceResult(
                id,
                "employee-service",
                RECIPIENT,
                new EmailPreferenceSettings(true, "en-us", hours),
                2,
                "create-process",
                "update-process",
                now.minusSeconds(60),
                now
        );

        PreferenceResponse response =
                PreferenceApiMapper.toResponse(result);

        assertThat(response.preferenceId()).isEqualTo(id);
        assertThat(response.recipientEmail()).isEqualTo(RECIPIENT);
        assertThat(response.version()).isEqualTo(2L);
        assertThat(response.settings().localeTag()).isEqualTo("en-US");

        if (includeQuietHours) {
            assertThat(response.settings().quietHours()).isEqualTo(
                    new PreferenceResponse.QuietHoursResponse(
                            "07:00",
                            "09:00",
                            "UTC"
                    )
            );
        } else {
            assertThat(response.settings().quietHours()).isNull();
        }

        assertThat(response.toString()).doesNotContain(RECIPIENT);
        assertThat(new PreferenceCreateRequest(
                RECIPIENT,
                validSettings()
        ).toString()).doesNotContain(RECIPIENT);
        assertThat(new PreferenceLookupRequest(RECIPIENT).toString())
                .doesNotContain(RECIPIENT);
    }

    private static PreferenceSettingsRequest validSettings() {
        return new PreferenceSettingsRequest(true, "en-IN", null);
    }

    private static PreferenceSettingsRequest settingsWithHours(
            String start,
            String end,
            String zone
    ) {
        return new PreferenceSettingsRequest(
                true,
                "en-IN",
                new PreferenceSettingsRequest.QuietHoursRequest(
                        start,
                        end,
                        zone
                )
        );
    }

    private static Set<String> invalidFields(Object request) {
        return validator.validate(request)
                .stream()
                .map(violation ->
                        violation.getPropertyPath().toString()
                )
                .collect(Collectors.toUnmodifiableSet());
    }
}