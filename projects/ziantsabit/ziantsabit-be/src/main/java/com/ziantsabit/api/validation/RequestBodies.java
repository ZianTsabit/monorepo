package com.ziantsabit.api.validation;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.springframework.stereotype.Component;

import com.ziantsabit.api.exception.ApiException;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Path;
import jakarta.validation.Validator;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Turns a JSON request body into a validated request record.
 *
 * <p>{@link #read} is a create: the body is the whole record. {@link #patch}
 * is a JSON merge: the current state, as a record, with the body's fields laid
 * over it -- a field left out keeps its value, an explicit {@code null} clears
 * it -- and then the same validation as a create, so a required field stays
 * required without a separate partial-update mode.
 *
 * <p>Either way, every problem is reported at once as a 400 of
 * {@code {"field": ["message", ...]}}, keyed by the JSON (snake_case) name.
 */
@Component
public class RequestBodies {

    private final JsonMapper json;
    private final Validator validator;

    public RequestBodies(JsonMapper json, Validator validator) {
        this.json = json;
        this.validator = validator;
    }

    public <T> T read(JsonNode body, Class<T> type) {
        return validate(convert(object(body), type));
    }

    public <T> T patch(T current, JsonNode body, Class<T> type) {
        ObjectNode merged = json.valueToTree(current);
        merged.setAll(object(body));
        return validate(convert(merged, type));
    }

    private ObjectNode object(JsonNode body) {
        if (body == null || body.isNull() || body.isMissingNode()) {
            return json.createObjectNode();
        }
        if (!(body instanceof ObjectNode object)) {
            throw ApiException.nonField(Messages.NOT_AN_OBJECT);
        }
        return object;
    }

    private <T> T convert(ObjectNode body, Class<T> type) {
        try {
            return json.treeToValue(body, type);
        } catch (DatabindException wrongType) {
            String field = wrongType.getPath().isEmpty() ? null : wrongType.getPath().getFirst().getPropertyName();
            if (field == null) {
                throw ApiException.nonField(Messages.INVALID);
            }
            throw ApiException.field(field, typeMessage(wrongType));
        }
    }

    private <T> T validate(T request) {
        Set<ConstraintViolation<T>> violations = validator.validate(request);
        if (violations.isEmpty()) {
            return request;
        }
        // Sorted, so the same bad body always gets the same response.
        Map<String, List<String>> errors = new TreeMap<>();
        for (ConstraintViolation<T> violation : violations) {
            errors.computeIfAbsent(fieldOf(violation.getPropertyPath()), field -> new java.util.ArrayList<>())
                    .add(violation.getMessage());
        }
        errors.values().forEach(messages -> messages.sort(null));
        throw ApiException.invalid(new LinkedHashMap<>(errors));
    }

    /** The top-level JSON name: a problem inside a list is the list's problem. */
    private static String fieldOf(Path path) {
        for (Path.Node node : path) {
            // The JSON is snake_case (spring.jackson.property-naming-strategy).
            return node.getName().replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(java.util.Locale.ROOT);
        }
        return "non_field_errors";
    }

    private static String typeMessage(DatabindException wrongType) {
        Class<?> target = targetType(wrongType);
        if (target == null) {
            return Messages.INVALID;
        }
        if (target == Instant.class) {
            return "Enter a valid date and time, as ISO 8601.";
        }
        if (target == Boolean.class || target == boolean.class) {
            return "Must be true or false.";
        }
        if (target == Integer.class || target == int.class) {
            return "Must be a whole number.";
        }
        if (Collection.class.isAssignableFrom(target)) {
            return "Must be a list.";
        }
        if (target == String.class) {
            return "Must be a string.";
        }
        return Messages.INVALID;
    }

    private static Class<?> targetType(DatabindException wrongType) {
        if (wrongType instanceof tools.jackson.databind.exc.MismatchedInputException mismatch) {
            return mismatch.getTargetType();
        }
        return null;
    }
}
