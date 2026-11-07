package com.ziantsabit.api.util;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

import com.ziantsabit.api.dto.LabelCount;

/**
 * Free-form labels: a post's tags and a book's genres, which are the same idea
 * twice and share every rule here so the two cannot drift apart.
 */
public final class Labels {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    private Labels() {
    }

    /**
     * Trim, collapse inner whitespace, drop blanks, and drop repeats without
     * regard to case -- keeping the first spelling seen, since that is the one
     * the author chose to display. Order is otherwise preserved.
     */
    public static List<String> clean(List<String> labels) {
        Set<String> seen = new HashSet<>();
        List<String> cleaned = new ArrayList<>();
        for (String label : labels == null ? List.<String>of() : labels) {
            String text = String.join(" ", WHITESPACE.split(label.strip()));
            if (text.isEmpty() || !seen.add(fold(text))) {
                continue;
            }
            cleaned.add(text);
        }
        return cleaned;
    }

    /**
     * Case-insensitive identity for a label: Python's {@code str.casefold()},
     * near enough. Upper-casing first is what folds "ß" to "ss".
     */
    public static String fold(String label) {
        return label.toUpperCase(Locale.ROOT).toLowerCase(Locale.ROOT);
    }

    /**
     * Fold per-spelling counts into one row per label, commonest first.
     *
     * <p>"Sci-Fi" and "sci-fi" are one filter option, not two -- the filter
     * matches either way round, so offering both would be offering it twice
     * with its count split between them. The commonest spelling names the row;
     * a tie goes to the alphabetically first, and rows with equal counts are
     * ordered alphabetically, so the answer never depends on row order.
     */
    public static List<LabelCount> counts(List<LabelCount> perSpelling, int limit) {
        Map<String, Map<String, Long>> byLabel = new LinkedHashMap<>();
        for (LabelCount row : perSpelling) {
            byLabel.computeIfAbsent(fold(row.name()), key -> new TreeMap<>())
                    .merge(row.name(), row.count(), Long::sum);
        }
        List<LabelCount> labels = new ArrayList<>();
        for (Map<String, Long> spellings : byLabel.values()) {
            String name = null;
            long best = -1;
            long total = 0;
            // TreeMap iterates alphabetically, so a strict > keeps the first of
            // equally common spellings.
            for (Map.Entry<String, Long> spelling : spellings.entrySet()) {
                total += spelling.getValue();
                if (spelling.getValue() > best) {
                    best = spelling.getValue();
                    name = spelling.getKey();
                }
            }
            labels.add(new LabelCount(name, total));
        }
        labels.sort(Comparator.comparingLong(LabelCount::count).reversed()
                .thenComparing(label -> fold(label.name())));
        return labels.size() > limit ? labels.subList(0, limit) : labels;
    }
}
