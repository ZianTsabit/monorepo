package com.ziantsabit.api.model;

import java.time.Instant;
import java.util.Map;

/**
 * The editable content of one fixed page -- the CV or the About page. {@code data}
 * is one JSON document in the shape {@code PageDocuments} guarantees.
 */
public record PageContent(String key, Map<String, Object> data, Instant updatedAt) {
}
