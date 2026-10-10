package com.ziantsabit.api.exception;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;

/**
 * A refusal with a status and the JSON body to send with it.
 *
 * <p>The bodies keep the two shapes DRF used, because the SPA's error handling
 * ({@code describe()} in {@code services/api.ts}) is written against them:
 * {@code {"detail": "..."}} for anything not tied to an input, and
 * {@code {"field": ["...", ...]}} for validation, which the admin forms hang off
 * the matching input. A message under {@code non_field_errors} is promoted to
 * the form's headline.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final Map<String, Object> body;

    public ApiException(HttpStatus status, Map<String, Object> body) {
        super(String.valueOf(body));
        this.status = status;
        this.body = body;
    }

    public HttpStatus status() {
        return status;
    }

    public Map<String, Object> body() {
        return body;
    }

    public static ApiException detail(HttpStatus status, String detail) {
        return new ApiException(status, Map.of("detail", detail));
    }

    public static ApiException notFound(String detail) {
        return detail(HttpStatus.NOT_FOUND, detail);
    }

    /** One message against one field, e.g. an unknown {@code ?ordering=}. */
    public static ApiException field(String field, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(field, List.of(message));
        return new ApiException(HttpStatus.BAD_REQUEST, body);
    }

    public static ApiException invalid(Map<String, Object> errors) {
        return new ApiException(HttpStatus.BAD_REQUEST, errors);
    }

    public static ApiException nonField(String message) {
        return field("non_field_errors", message);
    }
}
