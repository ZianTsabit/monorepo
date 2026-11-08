package com.ziantsabit.api.util;

import java.text.Normalizer;
import java.util.Locale;
import java.util.function.Predicate;

/** URL slugs: ASCII, lower case, hyphen-separated. */
public final class Slugs {

    private Slugs() {
    }

    /**
     * Fold to ASCII, lower-case, drop punctuation, hyphenate whitespace.
     *
     * <p>Don't change the rules lightly: the slugs already made are in the
     * database and in every link anyone has shared, and a title edited later
     * must not slugify differently from one edited before.
     */
    public static String slugify(String value) {
        String ascii = Normalizer.normalize(value, Normalizer.Form.NFKD).replaceAll("[^\\x00-\\x7F]", "");
        String cleaned = ascii.toLowerCase(Locale.ROOT).replaceAll("[^\\w\\s-]", "");
        return cleaned.replaceAll("[-\\s]+", "-").replaceAll("^[-_]+|[-_]+$", "");
    }

    /**
     * {@code base}, or {@code base-2}, {@code base-3}, ... -- the first one not
     * already taken, truncated so the suffix still fits in the column.
     */
    public static String unique(String base, int maxLength, Predicate<String> taken) {
        String slug = truncate(base, maxLength);
        for (int suffix = 2; taken.test(slug); suffix++) {
            String tail = "-" + suffix;
            slug = truncate(base, maxLength - tail.length()) + tail;
        }
        return slug;
    }

    private static String truncate(String value, int length) {
        return value.length() <= length ? value : value.substring(0, length);
    }
}
