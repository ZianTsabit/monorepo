package com.ziantsabit.api.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziantsabit.api.model.Post;
import com.ziantsabit.api.support.ApiTestBase;
import com.ziantsabit.api.support.Response;

/** PATCH is a JSON merge onto the stored record, then validated as a whole. */
class PatchSemanticsTest extends ApiTestBase {

    Post post;

    @BeforeEach
    void fixtures() {
        createUser();
        post = published("Merged", p -> {
            p.excerpt = "Kept";
            p.tags = List.of("spring");
        });
    }

    String detail() {
        return "/api/posts/" + post.slug() + "/";
    }

    static Map<String, Object> withNull(String field) {
        Map<String, Object> body = new HashMap<>();
        body.put(field, null);
        return body;
    }

    @Test
    void aFieldLeftOutKeepsItsValue() {
        Response response = owner().patch(detail(), body("title", "Renamed"));
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.string("title")).isEqualTo("Renamed");
        assertThat(response.string("excerpt")).isEqualTo("Kept");
        assertThat(response.get("tags")).isEqualTo(List.of("spring"));
        assertThat(response.string("slug")).isEqualTo(post.slug());
    }

    @Test
    void nullClearsANullableField() {
        Response response = owner().patch(detail(), withNull("published_at"));
        assertThat(response.status()).isEqualTo(200);
        // Published with no date is stamped now on save, so the value is fresh, not the old one.
        assertThat(response.string("published_at")).isNotNull();
        assertThat(row("post", post.id()).get("published_at")).isNotNull();
    }

    @Test
    void nullOnARequiredFieldIsRefused() {
        Response response = owner().patch(detail(), withNull("title"));
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.get("title")).isEqualTo(List.of("This field is required."));
        assertThat(row("post", post.id()).get("title")).isEqualTo("Merged");
    }

    @Test
    void aBlankRequiredFieldSaysBlankNotRequired() {
        Response response = owner().patch(detail(), body("title", "   "));
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.get("title")).isEqualTo(List.of("This field may not be blank."));
    }

    @Test
    void aValueOfTheWrongTypeIsAFieldError() {
        Response response = owner().patch(detail(), body("published_at", "last tuesday"));
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.get("published_at")).isEqualTo(List.of("Enter a valid date and time, as ISO 8601."));

        Response notABool = owner().patch(detail(), body("comments_enabled", List.of()));
        assertThat(notABool.status()).isEqualTo(400);
        assertThat(notABool.get("comments_enabled")).isEqualTo(List.of("Must be true or false."));
    }

    @Test
    void readOnlyFieldsSentBackAreIgnored() {
        Response response = owner().patch(detail(), body("title", "Again", "view_count", 9999, "id", 42));
        assertThat(response.status()).isEqualTo(200);
        assertThat(((Number) response.get("view_count")).longValue()).isZero();
        assertThat(((Number) response.get("id")).longValue()).isEqualTo(post.id());
    }

    @Test
    void everyProblemIsReportedAtOnce() {
        Response response = owner().patch(detail(),
                body("title", "", "cover_image_url", "not a url", "theme", "sepia"));
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.map()).containsOnlyKeys("title", "cover_image_url", "theme");
    }
}
