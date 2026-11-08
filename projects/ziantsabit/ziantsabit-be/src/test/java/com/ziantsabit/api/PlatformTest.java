package com.ziantsabit.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ziantsabit.api.support.ApiTestBase;
import com.ziantsabit.api.support.Response;

/** The platform around the resources: accounts, schema, docs, health, hosts and error shapes. */
class PlatformTest extends ApiTestBase {

    @Test
    void anInactiveAccountIsRefused() {
        createUser();
        jdbc.sql("UPDATE app_user SET enabled = false").update();
        Response response = owner().post("/api/posts/", body("title", "Hi"));
        assertThat(response.status()).isEqualTo(403);
        assertThat(response.string("detail")).isEqualTo("User inactive or deleted.");
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
    void swaggerUiIsServed() {
        assertThat(client.get("/api/docs/", Map.of("Accept", "text/html")).status()).isIn(200, 302);
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
    void errorsAreJsonWithADetail() {
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
        assertThat(jdbc.sql("SELECT count(*) FROM app_user WHERE username = 'admin'").query(Long.class).single())
                .isEqualTo(1);
    }

    @Test
    void nothingButTheSeededPagesExistsOnAFreshDatabase() {
        assertThat(count("post")).isZero();
        assertThat(jdbc.sql("SELECT key FROM page_content ORDER BY key").query(String.class).list())
                .isEqualTo(List.of("about", "cv"));
    }
}
