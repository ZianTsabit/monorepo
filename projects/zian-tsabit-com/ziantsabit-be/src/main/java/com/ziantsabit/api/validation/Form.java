package com.ziantsabit.api.validation;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

import com.ziantsabit.api.exception.ApiException;

/**
 * A JSON request body, read field by field the way a DRF serializer read it.
 *
 * <p>Each accessor returns {@link Optional#empty()} when the field was not sent
 * <em>or</em> was rejected, recording the rejection under the field's name; the
 * caller applies whatever came back present. Once every field has been read,
 * {@link #check()} throws one 400 carrying all of the messages at once, so a
 * form can mark every bad input in a single round trip rather than one per
 * submit.
 *
 * <p>The messages are DRF's own, word for word. The SPA shows them beside the
 * matching input, and the admin forms were written against that wording.
 *
 * <p>{@code partial} is PATCH: a missing required field is simply not being
 * changed. On POST and PUT it is "This field is required."
 */
public final class Form {

    private static final Pattern SLUG = Pattern.compile("[-a-zA-Z0-9_]+");
    private static final Pattern INTEGRAL = Pattern.compile("\\s*[-+]?\\d+(\\.0*)?\\s*");
    private static final DateTimeFormatter LOCAL_DATE_TIME = new DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .append(DateTimeFormatter.ISO_LOCAL_DATE)
            .optionalStart().appendLiteral('T').optionalEnd()
            .optionalStart().appendLiteral(' ').optionalEnd()
            .append(DateTimeFormatter.ISO_LOCAL_TIME)
            .toFormatter();
    private static final DateTimeFormatter OFFSET_DATE_TIME = new DateTimeFormatterBuilder()
            .append(LOCAL_DATE_TIME)
            .appendOffset("+HH:MM:ss", "Z")
            .toFormatter();

    private final Map<String, Object> data;
    private final boolean partial;
    private final Map<String, Object> errors = new LinkedHashMap<>();

    private Form(Map<String, Object> data, boolean partial) {
        this.data = data;
        this.partial = partial;
    }

    /** Wrap a request body; anything but a JSON object is refused outright. */
    @SuppressWarnings("unchecked")
    public static Form of(Object body, boolean partial) {
        if (body == null) {
            return new Form(Map.of(), partial);
        }
        if (!(body instanceof Map<?, ?> map)) {
            throw ApiException.nonField(
                    "Invalid data. Expected a dictionary, but got " + pythonType(body) + ".");
        }
        return new Form((Map<String, Object>) map, partial);
    }

    public boolean partial() {
        return partial;
    }

    public boolean has(String field) {
        return data.containsKey(field);
    }

    /** Throw the accumulated field errors, if there are any. */
    public void check() {
        if (!errors.isEmpty()) {
            throw ApiException.invalid(new LinkedHashMap<>(errors));
        }
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    /** Record a message against a field, as a serializer's validate_<field> would. */
    public void error(String field, String message) {
        errors.put(field, List.of(message));
    }

    /** Record a structured error -- a nested document's or a list's. */
    public void error(String field, Object detail) {
        errors.put(field, detail);
    }

    // --- field types ------------------------------------------------------

    /**
     * A CharField: trimmed, blank refused unless allowed, length-capped.
     *
     * <p>Numbers are accepted and turned into their string form, as DRF did;
     * booleans, objects and lists are "Not a valid string."
     */
    public Optional<String> text(String field, boolean required, boolean allowBlank, int maxLength) {
        Object raw = present(field, required);
        if (raw == ABSENT) {
            return Optional.empty();
        }
        return coerceText(field, raw, allowBlank, maxLength);
    }

    /** A SlugField: letters, digits, hyphens and underscores; blank is allowed. */
    public Optional<String> slug(String field, int maxLength) {
        Optional<String> value = text(field, false, true, maxLength);
        if (value.isPresent() && !value.get().isEmpty() && !SLUG.matcher(value.get()).matches()) {
            error(field, "Enter a valid \"slug\" consisting of letters, numbers, underscores or hyphens.");
            return Optional.empty();
        }
        return value;
    }

    /** A URLField; blank is allowed and means "no image". */
    public Optional<String> url(String field, int maxLength) {
        Optional<String> value = text(field, false, true, maxLength);
        if (value.isPresent() && !value.get().isEmpty() && !Urls.isValid(value.get())) {
            error(field, "Enter a valid URL.");
            return Optional.empty();
        }
        return value;
    }

    /** A ChoiceField over a fixed set of strings. */
    public Optional<String> choice(String field, boolean required, Collection<String> choices, boolean allowBlank) {
        Object raw = present(field, required);
        if (raw == ABSENT) {
            return Optional.empty();
        }
        if (raw == null) {
            error(field, "This field may not be null.");
            return Optional.empty();
        }
        String text = pythonStr(raw);
        if (text.isEmpty() && allowBlank && raw instanceof String) {
            return Optional.of("");
        }
        if (!(raw instanceof Boolean) && choices.contains(text)) {
            return Optional.of(text);
        }
        error(field, "\"" + text + "\" is not a valid choice.");
        return Optional.empty();
    }

    /** A BooleanField, accepting the spellings DRF did ("true", "on", 1, ...). */
    public Optional<Boolean> bool(String field) {
        Object raw = present(field, false);
        if (raw == ABSENT) {
            return Optional.empty();
        }
        if (raw == null) {
            error(field, "This field may not be null.");
            return Optional.empty();
        }
        if (raw instanceof Boolean flag) {
            return Optional.of(flag);
        }
        if (raw instanceof Number number) {
            // Compared by value, as Python's set lookup does: 1, 1.0 and True
            // are one key there.
            BigDecimal decimal = new BigDecimal(number.toString());
            if (decimal.compareTo(BigDecimal.ONE) == 0) {
                return Optional.of(true);
            }
            if (decimal.signum() == 0) {
                return Optional.of(false);
            }
        }
        if (raw instanceof String text) {
            if (Set.of("t", "T", "y", "Y", "yes", "Yes", "YES", "true", "True", "TRUE", "on", "On", "ON", "1")
                    .contains(text)) {
                return Optional.of(true);
            }
            if (Set.of("f", "F", "n", "N", "no", "No", "NO", "false", "False", "FALSE", "off", "Off", "OFF", "0")
                    .contains(text)) {
                return Optional.of(false);
            }
        }
        error(field, "Must be a valid boolean.");
        return Optional.empty();
    }

    /**
     * A nullable DateTimeField. The optional's value is itself optional: present
     * and empty means the field was sent as null, which clears it.
     *
     * <p>ISO 8601, with or without an offset; a naive time is read as UTC, the
     * server's time zone, and a bare date as its midnight -- what Django's
     * parser accepted.
     */
    public Optional<Optional<Instant>> nullableDateTime(String field) {
        Object raw = present(field, false);
        if (raw == ABSENT) {
            return Optional.empty();
        }
        if (raw == null) {
            return Optional.of(Optional.empty());
        }
        Instant parsed = raw instanceof String text ? parseDateTime(text.strip()) : null;
        if (parsed == null) {
            error(field, "Datetime has wrong format. Use one of these formats instead: "
                    + "YYYY-MM-DDThh:mm[:ss[.uuuuuu]][+HH:MM|-HH:MM|Z].");
            return Optional.empty();
        }
        // Postgres keeps microseconds; trimming here means the response to a
        // write is the value a later read will return.
        return Optional.of(Optional.of(parsed.truncatedTo(ChronoUnit.MICROS)));
    }

    /** A nullable IntegerField with a floor. Present-and-empty means null. */
    public Optional<Optional<Integer>> nullableInteger(String field, int minValue) {
        Object raw = present(field, false);
        if (raw == ABSENT) {
            return Optional.empty();
        }
        if (raw == null) {
            return Optional.of(Optional.empty());
        }
        Integer value = integerOf(raw);
        if (value == null) {
            error(field, "A valid integer is required.");
            return Optional.empty();
        }
        if (value < minValue) {
            error(field, "Ensure this value is greater than or equal to " + minValue + ".");
            return Optional.empty();
        }
        return Optional.of(Optional.of(value));
    }

    /**
     * A list of short labels -- a post's tags, a book's genres.
     *
     * <p>Each item is a CharField in its own right: trimmed, capped, and refused
     * when blank. A bad item is reported under its index, e.g.
     * {@code {"tags": {"0": ["This field may not be blank."]}}}.
     */
    public Optional<List<String>> labels(String field, int maxItemLength) {
        Object raw = present(field, false);
        if (raw == ABSENT) {
            return Optional.empty();
        }
        if (raw == null) {
            error(field, "This field may not be null.");
            return Optional.empty();
        }
        if (!(raw instanceof List<?> items)) {
            error(field, "Expected a list of items but got type \"" + pythonType(raw) + "\".");
            return Optional.empty();
        }
        List<String> values = new ArrayList<>();
        Map<String, Object> itemErrors = new LinkedHashMap<>();
        for (int index = 0; index < items.size(); index++) {
            Object item = items.get(index);
            List<String> problems = new ArrayList<>();
            String text = null;
            if (item == null) {
                problems.add("This field may not be null.");
            } else {
                text = textError(item, false, maxItemLength, problems);
            }
            if (problems.isEmpty()) {
                values.add(text);
            } else {
                itemErrors.put(Integer.toString(index), problems);
            }
        }
        if (!itemErrors.isEmpty()) {
            error(field, itemErrors);
            return Optional.empty();
        }
        return Optional.of(values);
    }

    /** Any JSON value but null -- a page document, checked by its normaliser. */
    public Optional<Object> json(String field, boolean required) {
        Object raw = present(field, required);
        if (raw == ABSENT) {
            return Optional.empty();
        }
        if (raw == null) {
            error(field, "This field may not be null.");
            return Optional.empty();
        }
        return Optional.of(raw);
    }

    /**
     * A reference by slug to another row, resolved by {@code lookup}.
     *
     * <p>An unknown slug is "Object with slug=... does not exist.", as DRF's
     * SlugRelatedField put it; anything that is not a string or a number is
     * "Invalid value."
     */
    public <T> Optional<T> slugReference(String field, boolean required, Function<String, Optional<T>> lookup) {
        Object raw = present(field, required);
        if (raw == ABSENT) {
            return Optional.empty();
        }
        if (raw == null) {
            error(field, "This field may not be null.");
            return Optional.empty();
        }
        if (!(raw instanceof String) && !(raw instanceof Number) || raw instanceof Boolean) {
            error(field, "Invalid value.");
            return Optional.empty();
        }
        String slug = pythonStr(raw);
        Optional<T> found = lookup.apply(slug);
        if (found.isEmpty()) {
            error(field, "Object with slug=" + slug + " does not exist.");
        }
        return found;
    }

    // --- internals --------------------------------------------------------

    private static final Object ABSENT = new Object();

    private Object present(String field, boolean required) {
        if (!data.containsKey(field)) {
            if (required && !partial) {
                error(field, "This field is required.");
            }
            return ABSENT;
        }
        return data.get(field);
    }

    private Optional<String> coerceText(String field, Object raw, boolean allowBlank, int maxLength) {
        if (raw == null) {
            error(field, "This field may not be null.");
            return Optional.empty();
        }
        List<String> problems = new ArrayList<>();
        String text = textError(raw, allowBlank, maxLength, problems);
        if (!problems.isEmpty()) {
            errors.put(field, problems);
            return Optional.empty();
        }
        return Optional.of(text);
    }

    /** CharField's checks, in its order; returns the cleaned text. */
    private static String textError(Object raw, boolean allowBlank, int maxLength, List<String> problems) {
        if (raw instanceof Boolean || !(raw instanceof String || raw instanceof Number)) {
            problems.add("Not a valid string.");
            return null;
        }
        String text = pythonStr(raw).strip();
        if (text.isEmpty()) {
            if (!allowBlank) {
                problems.add("This field may not be blank.");
            }
            return "";
        }
        if (maxLength > 0 && text.codePointCount(0, text.length()) > maxLength) {
            problems.add("Ensure this field has no more than " + maxLength + " characters.");
        }
        return text;
    }

    private static Integer integerOf(Object raw) {
        if (raw instanceof Boolean) {
            return null;
        }
        if (raw instanceof Integer || raw instanceof Long || raw instanceof BigInteger) {
            BigInteger big = new BigInteger(raw.toString());
            return big.bitLength() < 32 ? big.intValue() : null;
        }
        if (raw instanceof Double || raw instanceof Float || raw instanceof BigDecimal) {
            BigDecimal decimal = new BigDecimal(raw.toString());
            try {
                return decimal.intValueExact();
            } catch (ArithmeticException notIntegral) {
                return null;
            }
        }
        if (raw instanceof String text && INTEGRAL.matcher(text).matches()) {
            String digits = text.strip();
            int dot = digits.indexOf('.');
            if (dot >= 0) {
                digits = digits.substring(0, dot);
            }
            try {
                return Integer.parseInt(digits);
            } catch (NumberFormatException tooLarge) {
                return null;
            }
        }
        return null;
    }

    private static Instant parseDateTime(String text) {
        try {
            return OffsetDateTime.parse(text, OFFSET_DATE_TIME).toInstant();
        } catch (DateTimeException ignored) {
            // no offset: try naive
        }
        try {
            return LocalDateTime.parse(text, LOCAL_DATE_TIME).toInstant(ZoneOffset.UTC);
        } catch (DateTimeException ignored) {
            // not a datetime at all: try a bare date
        }
        try {
            return LocalDate.parse(text).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (DateTimeException ignored) {
            return null;
        }
    }

    /** Python's str() of a JSON scalar, which is what DRF compared and echoed. */
    private static String pythonStr(Object raw) {
        if (raw instanceof Boolean flag) {
            return flag ? "True" : "False";
        }
        if (raw instanceof Number number) {
            return numberText(number);
        }
        return raw.toString();
    }

    private static String numberText(Number number) {
        if (number instanceof Double || number instanceof Float || number instanceof BigDecimal) {
            BigDecimal decimal = new BigDecimal(number.toString());
            String plain = decimal.stripTrailingZeros().toPlainString();
            return plain.contains(".") ? plain : plain + ".0";
        }
        return number.toString();
    }

    /** The Python type name DRF's messages use for a JSON value. */
    public static String pythonType(Object value) {
        if (value instanceof String) {
            return "str";
        }
        if (value instanceof Boolean) {
            return "bool";
        }
        if (value instanceof Double || value instanceof Float || value instanceof BigDecimal) {
            return "float";
        }
        if (value instanceof Number) {
            return "int";
        }
        if (value instanceof Map) {
            return "dict";
        }
        if (value instanceof List) {
            return "list";
        }
        return "NoneType";
    }
}
