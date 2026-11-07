package com.ziantsabit.api.support;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import tools.jackson.databind.json.JsonMapper;

/** One response, with its JSON body parsed into plain maps and lists. */
public final class Response {

    private final int status;
    private final Map<String, List<String>> headers;
    private final String body;
    private final JsonMapper json;
    private Object parsed;

    Response(int status, Map<String, List<String>> headers, String body, JsonMapper json) {
        this.status = status;
        this.headers = headers;
        this.body = body;
        this.json = json;
    }

    public int status() {
        return status;
    }

    public String body() {
        return body;
    }

    /** The first value of a header, case-insensitively, or null. */
    public String header(String name) {
        return headers.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .findFirst().map(entry -> entry.getValue().get(0)).orElse(null);
    }

    public List<String> headers(String name) {
        return headers.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .findFirst().map(Map.Entry::getValue).orElse(List.of());
    }

    public Object json() {
        if (parsed == null) {
            parsed = json.readValue(body, Object.class);
        }
        return parsed;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> map() {
        return (Map<String, Object>) json();
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> list() {
        return (List<Map<String, Object>>) json();
    }

    public Object get(String key) {
        return map().get(key);
    }

    public String string(String key) {
        return (String) map().get(key);
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> results() {
        return (List<Map<String, Object>>) map().get("results");
    }

    /** {@code results[*].<field>} in order. */
    public List<Object> column(String field) {
        return results().stream().map(row -> row.get(field)).toList();
    }

    public List<Object> slugs() {
        return column("slug");
    }

    public Set<Object> slugSet() {
        return results().stream().map(row -> row.get("slug")).collect(Collectors.toSet());
    }

    @Override
    public String toString() {
        return status + " " + body;
    }
}
