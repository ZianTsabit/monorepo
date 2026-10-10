package com.ziantsabit.api.controller;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import com.ziantsabit.api.model.Post;
import com.ziantsabit.api.model.Reaction;
import com.ziantsabit.api.support.ApiTestBase;
import com.ziantsabit.api.support.Response;

/**
 * The two rate limits, with both turned down to one request an hour. Its own
 * class so it gets its own context: the rates are read at startup.
 */
@TestPropertySource(properties = {"app.comment-rate-limit=1/hour", "app.reaction-rate-limit=1/hour"})
class ThrottleTest extends ApiTestBase {

    Post post;

    @BeforeEach
    void fixtures() {
        post = published("Published Post");
    }

    @Test
    void postingACommentIsRateLimited() {
        var comment = body("post", post.slug(), "author_name", "Ada", "body", "Hi.");
        assertThat(client.post("/api/comments/", comment).status()).isEqualTo(201);
        Response second = client.post("/api/comments/", comment);
        assertThat(second.status()).isEqualTo(429);
        assertThat(second.string("detail")).startsWith("Request was throttled. Expected available in ");
        assertThat(second.header("Retry-After")).isNotNull();
    }

    @Test
    void readingAThreadIsNotRateLimited() {
        for (int i = 0; i < 3; i++) {
            assertThat(client.get("/api/comments/").status()).isEqualTo(200);
        }
    }

    @Test
    void theReactionBarIsRateLimited() {
        String url = "/api/posts/" + post.slug() + "/reactions/";
        assertThat(client.post(url, body("emoji", Reaction.EMOJI_VALUES.get(0), "visitor", "abc")).status())
                .isEqualTo(200);
        assertThat(client.get(url).status()).isEqualTo(429);
    }
}
