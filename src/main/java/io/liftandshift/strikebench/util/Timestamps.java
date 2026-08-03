package io.liftandshift.strikebench.util;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

/** Normalized parser for instants that have round-tripped through PostgreSQL text. */
public final class Timestamps {
    private Timestamps() {}

    public static Instant instant(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String value = raw.trim();
        if (value.chars().allMatch(Character::isDigit)) {
            return Instant.ofEpochMilli(Long.parseLong(value));
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException first) {
            String iso = value.replace(' ', 'T');
            if (iso.matches(".*[+-]\\d{2}$")) iso += ":00";
            return OffsetDateTime.parse(iso).toInstant();
        }
    }

    public static String isoInstant(String raw) {
        Instant parsed = instant(raw);
        return parsed == null ? null : parsed.toString();
    }
}
