package com.ziantsabit.api.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import tools.jackson.databind.json.JsonMapper;

/**
 * A real HTTP client against the running server -- not MockMvc -- so the
 * filters, the session cookie, CORS and CSRF all behave as a browser meets
 * them. One instance is one caller: anonymous, the owner by basic auth (which
 * skips CSRF, as DRF's force_authenticate did in the Django suite), or a
 * cookie-keeping browser for the session tests.
 */
public final class Api {

    private final String base;
    private final JsonMapper json;
    private final HttpClient http;
    private final Map<String, String> headers = new LinkedHashMap<>();

    Api(String base, JsonMapper json, boolean keepCookies) {
        this.base = base;
        this.json = json;
        HttpClient.Builder builder = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1);
        if (keepCookies) {
            builder.cookieHandler(new CookieManager());
        }
        this.http = builder.build();
    }

    public Api basicAuth(String username, String password) {
        headers.put("Authorization", "Basic " + Base64.getEncoder()
                .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8)));
        return this;
    }

    public Api header(String name, String value) {
        headers.put(name, value);
        return this;
    }

    // --- verbs ------------------------------------------------------------

    /** GET with query params given as name, value, name, value... */
    public Response get(String path, String... params) {
        return send("GET", path + query(params), null, null, Map.of());
    }

    public Response post(String path, Object body) {
        return send("POST", path, body, null, Map.of());
    }

    public Response post(String path, Object body, Map<String, String> extra) {
        return send("POST", path, body, null, extra);
    }

    public Response put(String path, Object body) {
        return send("PUT", path, body, null, Map.of());
    }

    public Response patch(String path, Object body) {
        return send("PATCH", path, body, null, Map.of());
    }

    public Response patch(String path, Object body, Map<String, String> extra) {
        return send("PATCH", path, body, null, extra);
    }

    public Response delete(String path) {
        return send("DELETE", path, null, null, Map.of());
    }

    public Response options(String path, Map<String, String> extra) {
        return send("OPTIONS", path, null, null, extra);
    }

    public Response get(String path, Map<String, String> extra) {
        return send("GET", path, null, null, extra);
    }

    /** A multipart/form-data POST; each part is a {@link Part}. */
    public Response multipart(String path, List<Part> parts) {
        String boundary = "----test" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        try {
            for (Part part : parts) {
                body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + part.name() + "\""
                        + (part.filename() == null ? "" : "; filename=\"" + part.filename() + "\"") + "\r\n"
                        + (part.contentType() == null ? "" : "Content-Type: " + part.contentType() + "\r\n")
                        + "\r\n").getBytes(StandardCharsets.UTF_8));
                body.write(part.bytes());
                body.write("\r\n".getBytes(StandardCharsets.UTF_8));
            }
            body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
        return send("POST", path, body.toByteArray(), "multipart/form-data; boundary=" + boundary, Map.of());
    }

    public record Part(String name, String filename, String contentType, byte[] bytes) {

        public static Part file(String filename, String contentType, byte[] bytes) {
            return new Part("file", filename, contentType, bytes);
        }
    }

    // --- internals --------------------------------------------------------

    private Response send(String method, String path, Object body, String contentType, Map<String, String> extra) {
        HttpRequest.BodyPublisher publisher;
        String type = contentType;
        if (body == null) {
            publisher = HttpRequest.BodyPublishers.noBody();
        } else if (body instanceof byte[] bytes) {
            publisher = HttpRequest.BodyPublishers.ofByteArray(bytes);
        } else {
            publisher = HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body));
            type = "application/json";
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(path.startsWith("http") ? path : base + path))
                .method(method, publisher)
                .header("Accept", "application/json");
        if (type != null) {
            request.header("Content-Type", type);
        }
        headers.forEach(request::header);
        extra.forEach(request::header);
        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.headers().map(), response.body(), json);
        } catch (IOException | InterruptedException failed) {
            throw new IllegalStateException(method + " " + path + " failed", failed);
        }
    }

    private static String query(String... params) {
        if (params.length == 0) {
            return "";
        }
        List<String> pairs = new ArrayList<>();
        for (int index = 0; index < params.length; index += 2) {
            pairs.add(URLEncoder.encode(params[index], StandardCharsets.UTF_8) + "="
                    + URLEncoder.encode(params[index + 1], StandardCharsets.UTF_8));
        }
        return "?" + String.join("&", pairs);
    }
}
