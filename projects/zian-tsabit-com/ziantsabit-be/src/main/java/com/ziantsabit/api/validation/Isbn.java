package com.ziantsabit.api.validation;

import java.util.Locale;
import java.util.regex.Pattern;

/** ISBNs as they are stored and checked. */
public final class Isbn {

    private static final Pattern WHITESPACE = Pattern.compile("\\s", Pattern.UNICODE_CHARACTER_CLASS);

    private Isbn() {
    }

    /**
     * No separators, an upper-case check digit. Two people copying one number
     * off one book write it with different hyphens and spaces; stripping them
     * makes the stored value the number itself.
     */
    public static String normalise(String value) {
        if (value == null) {
            return "";
        }
        return WHITESPACE.matcher(value).replaceAll("").replace("-", "").replace("–", "")
                .toUpperCase(Locale.ROOT);
    }

    /**
     * Whether a separator-free ISBN passes its own check digit -- which is what
     * catches a transposed pair, the typo that leaves an ISBN looking right and
     * matching nothing. Both schemes, since both are in print.
     */
    public static boolean isValid(String compact) {
        if (compact.length() == 10) {
            if (!digits(compact.substring(0, 9))) {
                return false;
            }
            char check = compact.charAt(9);
            if (!(isDigit(check) || check == 'X')) {
                return false;
            }
            int total = 0;
            for (int index = 0; index < 9; index++) {
                total += (10 - index) * (compact.charAt(index) - '0');
            }
            total += check == 'X' ? 10 : check - '0';
            return total % 11 == 0;
        }
        if (compact.length() == 13) {
            if (!digits(compact)) {
                return false;
            }
            int total = 0;
            for (int index = 0; index < 13; index++) {
                total += (compact.charAt(index) - '0') * (index % 2 == 0 ? 1 : 3);
            }
            return total % 10 == 0;
        }
        return false;
    }

    private static boolean digits(String text) {
        return text.chars().allMatch(c -> isDigit((char) c));
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }
}
