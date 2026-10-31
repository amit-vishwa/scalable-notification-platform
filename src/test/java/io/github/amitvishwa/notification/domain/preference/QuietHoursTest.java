package io.github.amitvishwa.notification.domain.preference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class QuietHoursTest {

    @ParameterizedTest
    @CsvSource({
            "22:00,07:00,2026-10-31T16:29:59Z,2026-10-31T16:29:59Z",
            "22:00,07:00,2026-10-31T16:30:00Z,2026-11-01T01:30:00Z",
            "22:00,07:00,2026-11-01T01:29:59Z,2026-11-01T01:30:00Z",
            "22:00,07:00,2026-11-01T01:30:00Z,2026-11-01T01:30:00Z",
            "09:00,17:00,2026-10-31T03:30:00Z,2026-10-31T11:30:00Z",
            "09:00,17:00,2026-10-31T11:30:00Z,2026-10-31T11:30:00Z"
    })
    void handlesDaytimeAndOvernightBoundaries(
            String start,
            String end,
            String input,
            String expected
    ) {
        QuietHours hours = hours(start, end, "Asia/Kolkata");
        Instant now = Instant.parse(input);

        assertThat(hours.nextEligibleAt(now))
                .isEqualTo(Instant.parse(expected));

        assertThat(hours.isQuietAt(now))
                .isEqualTo(!input.equals(expected));
    }

    @ParameterizedTest
    @CsvSource({
            "01:00,02:30,2026-03-08T06:30:00Z,2026-03-08T07:00:00Z",
            "00:30,01:30,2026-11-01T05:15:00Z,2026-11-01T05:30:00Z",
            "00:30,01:30,2026-11-01T06:15:00Z,2026-11-01T06:30:00Z",
            "01:45,02:30,2026-11-01T05:50:00Z,2026-11-01T06:00:00Z",
            "00:30,02:30,2026-11-01T05:50:00Z,2026-11-01T07:30:00Z"
    })
    void handlesDaylightSavingGapsAndRepeatedTimes(
            String start,
            String end,
            String input,
            String expected
    ) {
        QuietHours hours = hours(start, end, "America/New_York");
        Instant now = Instant.parse(input);
        Instant eligible = hours.nextEligibleAt(now);

        assertThat(hours.isQuietAt(now)).isTrue();
        assertThat(eligible).isEqualTo(Instant.parse(expected));
        assertThat(eligible).isAfter(now);
        assertThat(hours.isQuietAt(eligible)).isFalse();
    }

    @Test
    void handlesSkippedCalendarDate() {
        QuietHours hours = hours("22:00", "07:00", "Pacific/Apia");

        Instant eligible = hours.nextEligibleAt(
                Instant.parse("2011-12-30T09:30:00Z")
        );

        assertThat(eligible)
                .isEqualTo(Instant.parse("2011-12-30T17:00:00Z"));
        assertThat(hours.isQuietAt(eligible)).isFalse();
    }

    @Test
    void rejectsEqualEndpoints() {
        assertThrows(
                IllegalArgumentException.class,
                () -> hours("09:00", "09:00", "UTC")
        );
    }

    @Test
    void rejectsUnsupportedTimePrecision() {
        assertThrows(
                IllegalArgumentException.class,
                () -> hours("09:00:01", "17:00", "UTC")
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> hours("09:00", "17:00:00.000000001", "UTC")
        );
    }

    @Test
    void requiresNamedTimezoneAndNonNullInputs() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new QuietHours(
                        LocalTime.of(22, 0),
                        LocalTime.of(7, 0),
                        ZoneOffset.ofHours(2)
                )
        );
        assertThrows(
                NullPointerException.class,
                () -> new QuietHours(
                        null,
                        LocalTime.of(7, 0),
                        ZoneId.of("UTC")
                )
        );
        assertThrows(
                NullPointerException.class,
                () -> hours("22:00", "07:00", "UTC")
                        .nextEligibleAt(null)
        );
    }

    private static QuietHours hours(
            String start,
            String end,
            String zone
    ) {
        return new QuietHours(
                LocalTime.parse(start),
                LocalTime.parse(end),
                ZoneId.of(zone)
        );
    }
}