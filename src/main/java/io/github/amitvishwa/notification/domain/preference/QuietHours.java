package io.github.amitvishwa.notification.domain.preference;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.zone.ZoneOffsetTransition;
import java.time.zone.ZoneRules;
import java.util.Objects;
import java.util.Set;

public record QuietHours(
        LocalTime start,
        LocalTime end,
        ZoneId zone
) {

    private static final int MAX_BOUNDARY_STEPS = 16;
    private static final int MAX_LOOKAHEAD_DAYS = 2;

    private static final Set<String> NAMED_ZONES =
            Set.copyOf(ZoneId.getAvailableZoneIds());

    public QuietHours {
        Objects.requireNonNull(start, "start must not be null");
        Objects.requireNonNull(end, "end must not be null");
        Objects.requireNonNull(zone, "zone must not be null");

        if (start.equals(end)) {
            throw new IllegalArgumentException(
                    "Quiet-hours start and end must differ"
            );
        }

        if (start.getSecond() != 0
                || start.getNano() != 0
                || end.getSecond() != 0
                || end.getNano() != 0) {
            throw new IllegalArgumentException(
                    "Quiet hours must use HH:mm precision"
            );
        }

        if (!NAMED_ZONES.contains(zone.getId())) {
            throw new IllegalArgumentException(
                    "zone must be a named timezone"
            );
        }
    }

    public boolean isQuietAt(Instant instant) {
        Objects.requireNonNull(
                instant,
                "instant must not be null"
        );

        LocalTime time = instant.atZone(zone).toLocalTime();

        if (start.isBefore(end)) {
            return !time.isBefore(start) && time.isBefore(end);
        }

        // Overnight window, for example 22:00–07:00.
        return !time.isBefore(start) || time.isBefore(end);
    }

    public Instant nextEligibleAt(Instant now) {
        Objects.requireNonNull(now, "now must not be null");

        ZoneRules rules = zone.getRules();
        Instant candidate = now;

        for (int step = 0;
             step < MAX_BOUNDARY_STEPS && isQuietAt(candidate);
             step++) {

            candidate = nextBoundaryAfter(candidate, rules);
        }

        if (isQuietAt(candidate)) {
            // Fail closed rather than authorizing a quiet-hours send.
            throw new IllegalStateException(
                    "Unable to resolve the next eligible delivery time"
            );
        }

        return candidate;
    }

    private Instant nextBoundaryAfter(
            Instant cursor,
            ZoneRules rules
    ) {
        LocalDate date = cursor.atZone(zone).toLocalDate();
        Instant next = null;

        for (int day = 0; day <= MAX_LOOKAHEAD_DAYS; day++) {
            LocalDateTime localEnd =
                    date.plusDays(day).atTime(end);

            // Overlaps can have two offsets; gaps have none.
            for (ZoneOffset offset : rules.getValidOffsets(localEnd)) {
                Instant candidate = localEnd.toInstant(offset);

                if (candidate.isAfter(cursor)
                        && (next == null || candidate.isBefore(next))) {
                    next = candidate;
                }
            }
        }

        // A clock change can itself move outside the quiet window.
        ZoneOffsetTransition transition =
                rules.nextTransition(cursor);

        if (transition != null) {
            Instant candidate = transition.getInstant();

            if (candidate.isAfter(cursor)
                    && (next == null || candidate.isBefore(next))) {
                next = candidate;
            }
        }

        if (next == null) {
            throw new IllegalStateException(
                    "Unable to resolve the next quiet-hours boundary"
            );
        }

        return next;
    }
}