package com.ziantsabit.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ziantsabit.api.security.DjangoPasswordEncoder;
import com.ziantsabit.api.support.ApiTestBase;
import com.ziantsabit.api.support.Response;

/** What the Django project gave for free and the port has to provide itself. */
class PlatformTest extends ApiTestBase {

    // Computed with Python's hashlib.pbkdf2_hmac, which is exactly what
    // Django's PBKDF2PasswordHasher runs.
    static final String DJANGO_HASH =
            "pbkdf2_sha256$1000$abcdefghijklmnopqrstuv$yH2VGHMchEp1vrLNWnm89deW1DEjt3ZQbjqugpX9tLo=";

    @Test
    void aPasswordHashedByDjangoStillVerifies() {
        DjangoPasswordEncoder encoder = new DjangoPasswordEncoder(1000);
        assertThat(encoder.matches("pw-for-tests", DJANGO_HASH)).isTrue();
        assertThat(encoder.matches("wrong", DJANGO_HASH)).isFalse();
        assertThat(encoder.matches("pw-for-tests", "!unusable")).isFalse();
    }

    @Test
    void aHashWrittenHereIsInDjangosFormat() {
        String hash = new DjangoPasswordEncoder(1200000).encode("secret");
        assertThat(hash).matches("pbkdf2_sha256\\$1200000\\$[A-Za-z0-9]{22}\\$[A-Za-z0-9+/]{43}=");
    }

    @Test
    void anAccountCarriedOverFromDjangoCanWrite() {
        jdbc.sql("""
                INSERT INTO auth_user (password, is_superuser, username, first_name, last_name, email, is_staff,
                                       is_active, date_joined)
                VALUES (:hash, true, 'legacy', '', '', '', true, true, now())""")
                .param("hash", DJANGO_HASH).update();
        Response response = anonymous().basicAuth("legacy", "pw-for-tests").post("/api/posts/", body("title", "Hi"));
        assertThat(response.status()).isEqualTo(201);
    }

    @Test
    void anInactiveAccountIsRefused() {
        createUser();
        jdbc.sql("UPDATE auth_user SET is_active = false").update();
        Response response = owner().post("/api/posts/", body("title", "Hi"));
        assertThat(response.status()).isEqualTo(403);
        assertThat(response.string("detail")).isEqualTo("User inactive or deleted.");
    }

    @Test
    void theApiIndexListsTheResources() {
        Response response = client.get("/api/");
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.map().keySet()).containsExactly("posts", "books", "comments", "pages");
        assertThat(response.string("posts")).isEqualTo(base() + "/api/posts/");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theSchemaDescribesTheApiInSnakeCase() {
        Response response = client.get("/api/schema/");
        assertThat(response.status()).isEqualTo(200);
        Map<String, Object> paths = (Map<String, Object>) response.get("paths");
        assertThat(paths).containsKeys("/api/posts", "/api/posts/{slug}", "/api/posts/{slug}/reactions",
                "/api/books", "/api/comments", "/api/pages/{key}", "/api/uploads/images", "/api/auth/login");
        Map<String, Object> schemas = (Map<String, Object>) ((Map<String, Object>) response.get("components"))
                .get("schemas");
        Map<String, Object> post = (Map<String, Object>) ((Map<String, Object>) schemas.get("Post")).get("properties");
        assertThat(post).containsKeys("published_at", "comment_count", "cover_image_url");
        Map<String, Object> request = (Map<String, Object>) ((Map<String, Object>) schemas.get("PostRequest"))
                .get("properties");
        assertThat(request).containsKey("comments_enabled");
    }

    @Test
    void swaggerAndRedocAreServed() {
        Map<String, String> html = Map.of("Accept", "text/html");
        assertThat(client.get("/api/docs/", html).status()).isIn(200, 302);
        Response redoc = client.get("/api/redoc/", html);
        assertThat(redoc.status()).isEqualTo(200);
        assertThat(redoc.body()).contains("<redoc spec-url=\"/api/schema\">");
    }

    @Test
    void healthIsUp() {
        Response response = client.get("/actuator/health");
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.string("status")).isEqualTo("UP");
    }

    @Test
    void anUnknownHostIsRefused() {
        Response response = client.get(base().replace("localhost", "127.0.0.2") + "/api/posts/");
        assertThat(response.status()).isEqualTo(400);
    }

    @Test
    void errorsAreJsonInDrfsShape() {
        Response missing = client.get("/api/nothing-here/");
        assertThat(missing.status()).isEqualTo(404);
        assertThat(missing.string("detail")).isEqualTo("Not found.");

        createUser();
        Response wrongVerb = owner().post("/api/pages/", body());
        assertThat(wrongVerb.status()).isEqualTo(405);
        assertThat(wrongVerb.string("detail")).isEqualTo("Method \"POST\" not allowed.");
    }

    @Test
    void malformedJsonIsA400() {
        createUser();
        Response response = owner().post("/api/posts/", "{not json".getBytes());
        assertThat(response.status()).isIn(400, 415);
    }

    @Test
    void theAdminAccountIsCreatedFromTheEnvironmentOnlyOnce() {
        assertThat(accounts.exists("admin")).isFalse();
        // See AdminBootstrapTest for the startup path; this is the guard it relies on.
        accounts.create("admin", "first");
        assertThat(accounts.exists("admin")).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM auth_user WHERE username = 'admin'").query(Long.class).single())
                .isEqualTo(1);
    }

    @Test
    void nothingButTheSeededPagesExistsOnAFreshDatabase() {
        assertThat(count("myapp_post")).isZero();
        assertThat(jdbc.sql("SELECT key FROM myapp_pagecontent ORDER BY key").query(String.class).list())
                .isEqualTo(List.of("about", "cv"));
    }
}
