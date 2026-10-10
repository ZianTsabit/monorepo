package com.ziantsabit.api.validation;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.ziantsabit.api.exception.ApiException;

/** Validation for the hand-rolled query parameters every list route reads. */
public final class QueryParams {

    // Django's parse_date fallback: one- or two-digit month and day allowed.
    private static final Pattern LOOSE_DATE = Pattern.compile("(\\d{4})-(\\d{1,2})-(\\d{1,2})");

    private QueryParams() {
    }

    /** The parameter with surrounding whitespace removed, or "" when absent. */
    public static String trimmed(String value) {
        return value == null ? "" : value.strip();
    }

    /**
     * Turn an unrecognised value into a 400 that lists the valid ones.
     *
     * <p>Dropping an unknown filter silently would answer a typo'd
     * {@code ?ordering=titel} with the default order, which looks exactly like
     * the filter worked.
     */
    public static void rejectUnknown(String value, Collection<String> allowed, String field) {
        if (!allowed.contains(value)) {
            throw ApiException.field(field, "'" + value + "' is not a valid " + field + ". Choose from: "
                    + String.join(", ", allowed) + ".");
        }
    }

    /** A YYYY-MM-DD parameter, or null when absent or empty. */
    public static LocalDate date(String raw, String field) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        LocalDate parsed = parseDate(raw);
        if (parsed == null) {
            throw ApiException.field(field, "'" + raw + "' is not a valid date. Use YYYY-MM-DD.");
        }
        return parsed;
    }

    private static LocalDate parseDate(String raw) {
        for (DateTimeFormatter format : new DateTimeFormatter[] {
                DateTimeFormatter.ISO_LOCAL_DATE, DateTimeFormatter.BASIC_ISO_DATE}) {
            try {
                return LocalDate.parse(raw, format);
            } catch (DateTimeException ignored) {
                // try the next form
            }
        }
        Matcher loose = LOOSE_DATE.matcher(raw);
        if (!loose.matches()) {
            return null;
        }
        try {
            // Well-formed but impossible (2026-02-31) is still a 400.
            return LocalDate.of(
                    Integer.parseInt(loose.group(1)),
                    Integer.parseInt(loose.group(2)),
                    Integer.parseInt(loose.group(3)));
        } catch (DateTimeException impossible) {
            return null;
        }
    }
}
