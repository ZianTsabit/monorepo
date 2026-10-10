package com.ziantsabit.api.validation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * The shape of the CV and About page documents, and how one is normalised.
 *
 * <p>A page's {@code data} is one JSON document rather than columns: a CV is a
 * handful of lists of small records, only ever read and written whole, by one
 * person. Every write goes through {@link #normalise}, so what is stored is
 * always the canonical shape and the SPA renders it without defending against
 * a key that might be missing.
 *
 * <p><b>It fills defaults rather than rejecting a partial document</b> -- a CV
 * with no projects yet is a CV -- but a value of the wrong <em>kind</em> (a
 * string where a list belongs) is a 400 naming the field, because coercing it
 * would put a rendering error on a public page instead.
 *
 * <p><b>Unknown keys are dropped</b>: almost always a typo or a field from an
 * older shape, and keeping them would slowly fill the document with things
 * nothing reads.
 *
 * <p>Key order in each map is the order the SPA expects to iterate, which is
 * why everything is built as a LinkedHashMap.
 */
public final class PageDocuments {

    public static final List<String> KEYS = List.of("cv", "about");

    private PageDocuments() {
    }

    /** A wrong-kind value, reported as {@code {"<field>": ["Expected <what>."]}}. */
    public static final class Invalid extends RuntimeException {

        private final Map<String, Object> detail;

        public Invalid(String field, String expected) {
            super(field + ": expected " + expected);
            this.detail = Map.of(field, List.of("Expected " + expected + "."));
        }

        public Map<String, Object> detail() {
            return detail;
        }
    }

    /** The canonical document for {@code key}, built from whatever was sent. */
    public static Map<String, Object> normalise(String key, Object data) {
        if (data == null) {
            data = Map.of();
        }
        if (!(data instanceof Map<?, ?> document)) {
            throw new Invalid("data", "an object");
        }
        return switch (key) {
            case "cv" -> cv(document);
            case "about" -> about(document);
            default -> throw new IllegalArgumentException("No content shape for '" + key + "'");
        };
    }

    /** A fully-shaped but empty document, for a page nobody has filled in yet. */
    public static Map<String, Object> empty(String key) {
        return normalise(key, Map.of());
    }

    // --- CV -----------------------------------------------------------------

    private static Map<String, Object> cv(Map<?, ?> data) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", text(data.get("name"), "name"));
        out.put("location", text(data.get("location"), "location"));
        out.put("links", records(data.get("links"), "links", PageDocuments::link));
        // The headings are content, emoji included -- picking those is not
        // worth a deploy.
        out.put("summary", section(data.get("summary"), "summary", "📄 Summary",
                "body", (value, field) -> text(value, field)));
        out.put("experience", section(data.get("experience"), "experience", "💼 Experience",
                "entries", (value, field) -> records(value, field, PageDocuments::entry)));
        out.put("projects", section(data.get("projects"), "projects", "🛠️ Projects",
                "entries", (value, field) -> records(value, field, PageDocuments::entry)));
        out.put("skills", section(data.get("skills"), "skills", "⚙️ Skills",
                "groups", (value, field) -> records(value, field, PageDocuments::skillGroup)));
        out.put("education", section(data.get("education"), "education",
                "🎓 Education & Certifications",
                "entries", (value, field) -> records(value, field, PageDocuments::entry)));
        return out;
    }

    /**
     * One timeline row: a job, a project or a qualification -- one shape for
     * all three, since the SPA renders them through one component. An entry
     * with no title is dropped: it is what an "add" button leaves behind.
     */
    private static Map<String, Object> entry(Map<?, ?> item, String field) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("title", text(item.get("title"), field));
        entry.put("subtitle", text(item.get("subtitle"), field));
        entry.put("subtitle_link", text(item.get("subtitle_link"), field));
        entry.put("location", text(item.get("location"), field));
        entry.put("duration", text(item.get("duration"), field));
        entry.put("blurb", text(item.get("blurb"), field));
        entry.put("points", textList(item.get("points"), field));
        return ((String) entry.get("title")).isEmpty() ? null : entry;
    }

    /** A group needs a name to head it; an unnamed pile of chips says nothing. */
    private static Map<String, Object> skillGroup(Map<?, ?> item, String field) {
        Map<String, Object> group = new LinkedHashMap<>();
        group.put("label", text(item.get("label"), field));
        group.put("items", textList(item.get("items"), field));
        return ((String) group.get("label")).isEmpty() ? null : group;
    }

    /** A header link. {@code icon_url} is optional: the email link has always been an emoji. */
    private static Map<String, Object> link(Map<?, ?> item, String field) {
        Map<String, Object> link = new LinkedHashMap<>();
        link.put("label", text(item.get("label"), field));
        link.put("url", text(item.get("url"), field));
        link.put("icon_url", text(item.get("icon_url"), field));
        return ((String) link.get("label")).isEmpty() || ((String) link.get("url")).isEmpty() ? null : link;
    }

    /** A headed section: its heading (defaulted when blank) plus the one list it carries. */
    private static Map<String, Object> section(Object value, String field, String heading, String listName,
            BiFunction<Object, String, Object> build) {
        Map<?, ?> raw = object(value, field);
        Map<String, Object> out = new LinkedHashMap<>();
        String given = text(raw.get("heading"), field);
        out.put("heading", given.isEmpty() ? heading : given);
        out.put(listName, build.apply(raw.get(listName), field + "." + listName));
        return out;
    }

    // --- About --------------------------------------------------------------

    private static Map<String, Object> about(Map<?, ?> data) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", text(data.get("name"), "name"));
        out.put("headline", text(data.get("headline"), "headline"));
        out.put("location", text(data.get("location"), "location"));
        // The portrait's two faces; with no back image it simply does not flip.
        out.put("photo_front", text(data.get("photo_front"), "photo_front"));
        out.put("photo_back", text(data.get("photo_back"), "photo_back"));
        out.put("photo_alt", text(data.get("photo_alt"), "photo_alt"));
        // A free list, unlike the CV's fixed sections: a third heading is
        // something the owner should be able to add without a release.
        out.put("sections", records(data.get("sections"), "sections", PageDocuments::aboutSection));
        return out;
    }

    /** Either half alone is still worth rendering. */
    private static Map<String, Object> aboutSection(Map<?, ?> item, String field) {
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("heading", text(item.get("heading"), field));
        section.put("body", text(item.get("body"), field));
        return ((String) section.get("heading")).isEmpty() && ((String) section.get("body")).isEmpty()
                ? null : section;
    }

    // --- value kinds --------------------------------------------------------

    /** One string. Null reads as absent, which is how a form clears one. */
    private static String text(Object value, String field) {
        if (value == null) {
            return "";
        }
        if (!(value instanceof String text)) {
            throw new Invalid(field, "a string");
        }
        return text.strip();
    }

    /** Blanks dropped: an empty bullet renders as a lone marker. */
    private static List<String> textList(Object value, String field) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> items)) {
            throw new Invalid(field, "a list of strings");
        }
        List<String> out = new ArrayList<>();
        for (Object item : items) {
            String text = text(item, field);
            if (!text.isEmpty()) {
                out.add(text);
            }
        }
        return out;
    }

    private interface RecordBuilder {
        Map<String, Object> build(Map<?, ?> item, String field);
    }

    private static List<Map<String, Object>> records(Object value, String field, RecordBuilder normalise) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> items)) {
            throw new Invalid(field, "a list of objects");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> map)) {
                throw new Invalid(field, "a list of objects");
            }
            Map<String, Object> record = normalise.build(map, field);
            if (record != null) {
                out.add(record);
            }
        }
        return out;
    }

    private static Map<?, ?> object(Object value, String field) {
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw new Invalid(field, "an object");
        }
        return map;
    }
}
