package com.ziantsabit.api.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziantsabit.api.model.Post;
import com.ziantsabit.api.support.ApiTestBase;
import com.ziantsabit.api.support.Response;

/** /api/comments/. Ported from the Django suite's CommentAPITests (the throttle tests are in CommentThrottleTest). */
class CommentApiTest extends ApiTestBase {

    Post post;
    Post other;
    Post draft;

    @BeforeEach
    void fixtures() {
        createUser();
        post = published("Published Post");
        other = published("Another Post");
        draft = post("Draft Post");
    }

    static String detail(long id) {
        return "/api/comments/" + id + "/";
    }

    Map<String, Object> comment(String status) {
        return row("myapp_comment", comment(post, "Visitor", "Nice one.", status));
    }

    @Test
    void anonymousCanPostAComment() {
        Response response = client.post("/api/comments/",
                body("post", post.slug(), "author_name", "Ada", "body", "Good read."));
        assertThat(response.status()).isEqualTo(201);
        assertThat(response.string("author_name")).isEqualTo("Ada");
        assertThat(response.string("post")).isEqualTo(post.slug());
        assertThat(response.map().keySet()).containsExactly("id", "post", "post_title", "author_name", "body",
                "status", "created_at", "updated_at");
    }

    @Test
    void aNewCommentIsVisibleImmediately() {
        client.post("/api/comments/", body("post", post.slug(), "author_name", "Ada", "body", "Good read."));
        assertThat(client.get("/api/comments/", "post", post.slug()).column("body")).containsExactly("Good read.");
    }

    @Test
    void aVisitorCannotChooseTheStatus() {
        Response response = client.post("/api/comments/",
                body("post", post.slug(), "author_name", "Ada", "body", "Good read.", "status", "hidden"));
        assertThat(response.status()).isEqualTo(201);
        assertThat(response.string("status")).isEqualTo("published");
    }

    @Test
    void theOwnerMayPostAHiddenComment() {
        Response response = owner().post("/api/comments/",
                body("post", post.slug(), "author_name", "Zian", "body", "Note to self.", "status", "hidden"));
        assertThat(response.string("status")).isEqualTo("hidden");
    }

    @Test
    void aBlankNameIsRejected() {
        Response response = client.post("/api/comments/", body("post", post.slug(), "author_name", "   ", "body", "Hi."));
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.map()).containsKey("author_name");
    }

    @Test
    void aBlankBodyIsRejected() {
        Response response = client.post("/api/comments/",
                body("post", post.slug(), "author_name", "Ada", "body", "   \n "));
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.map()).containsKey("body");
    }

    @Test
    void anOverlongBodyIsRejected() {
        Response response = client.post("/api/comments/",
                body("post", post.slug(), "author_name", "Ada", "body", "x".repeat(2001)));
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.get("body")).isEqualTo(List.of("Ensure this field has no more than 2000 characters."));
    }

    @Test
    void theNameIsCollapsedButTheBodyKeepsItsNewlines() {
        Response response = client.post("/api/comments/",
                body("post", post.slug(), "author_name", "  Ada   Lovelace ", "body", "  One.\n\nTwo.  "));
        assertThat(response.string("author_name")).isEqualTo("Ada Lovelace");
        assertThat(response.string("body")).isEqualTo("One.\n\nTwo.");
    }

    @Test
    void anonymousCannotCommentOnADraft() {
        Response response = client.post("/api/comments/", body("post", draft.slug(), "author_name", "Ada", "body", "Hi."));
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.get("post")).isEqualTo(List.of("No post found with that slug."));
        assertThat(count("myapp_comment")).isZero();
    }

    @Test
    void anUnknownSlugIsRejected() {
        Response response = client.post("/api/comments/",
                body("post", "nothing-here", "author_name", "Ada", "body", "Hi."));
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.get("post")).isEqualTo(List.of("Object with slug=nothing-here does not exist."));
    }

    @Test
    void theThreadIsFilteredByPost() {
        comment(post, "Visitor", "On this one");
        comment(other, "Visitor", "On the other");
        assertThat(client.get("/api/comments/", "post", post.slug()).column("body")).containsExactly("On this one");
    }

    @Test
    void aThreadReadsOldestFirst() {
        comment(post, "Visitor", "First");
        comment(post, "Visitor", "Second");
        assertThat(client.get("/api/comments/", "post", post.slug()).column("body")).containsExactly("First", "Second");
    }

    @Test
    void newestFirstIsAvailableForTheAdmin() {
        comment(post, "Visitor", "First");
        comment(post, "Visitor", "Second");
        assertThat(owner().get("/api/comments/", "ordering", "newest").column("body"))
                .containsExactly("Second", "First");
    }

    @Test
    void anUnknownOrderingIsRejected() {
        assertThat(client.get("/api/comments/", "ordering", "oldst").status()).isEqualTo(400);
    }

    @Test
    void hiddenCommentsAreInvisibleToVisitors() {
        comment(post, "Visitor", "Fine");
        comment(post, "Visitor", "Spam", "hidden");
        assertThat(client.get("/api/comments/", "post", post.slug()).column("body")).containsExactly("Fine");
    }

    @Test
    void hiddenCommentsAreVisibleToTheOwner() {
        comment(post, "Visitor", "Spam", "hidden");
        assertThat(owner().get("/api/comments/", "status", "hidden").column("body")).containsExactly("Spam");
    }

    @Test
    void aHiddenCommentIsA404ToAVisitorByIdToo() {
        long id = comment(post, "Visitor", "Spam", "hidden");
        assertThat(client.get(detail(id)).status()).isEqualTo(404);
        assertThat(owner().get(detail(id)).status()).isEqualTo(200);
    }

    @Test
    void aDraftsCommentsAreInvisibleToVisitors() {
        comment(draft, "Visitor", "Early look");
        assertThat(client.get("/api/comments/", "post", draft.slug()).results()).isEmpty();
    }

    @Test
    void searchMatchesTheBodyOrTheName() {
        comment(post, "Ada", "About Postgres");
        comment(post, "Grace", "Something else");
        assertThat(client.get("/api/comments/", "search", "postgres").column("author_name")).containsExactly("Ada");
        assertThat(client.get("/api/comments/", "search", "grace").column("author_name")).containsExactly("Grace");
    }

    @Test
    void aRowCarriesItsPostsTitle() {
        comment(post, "Visitor", "Nice one.");
        assertThat(client.get("/api/comments/").results().get(0).get("post_title")).isEqualTo(post.title());
    }

    @Test
    void anonymousCannotHideAComment() {
        long id = comment(post, "Visitor", "Nice one.");
        assertThat(client.patch(detail(id), body("status", "hidden")).status()).isEqualTo(403);
    }

    @Test
    void anonymousCannotDeleteAComment() {
        long id = comment(post, "Visitor", "Nice one.");
        assertThat(client.delete(detail(id)).status()).isEqualTo(403);
        assertThat(count("myapp_comment")).isEqualTo(1);
    }

    @Test
    void theOwnerCanHideAndRestoreAComment() {
        long id = comment(post, "Visitor", "Nice one.");
        owner().patch(detail(id), body("status", "hidden"));
        assertThat(row("myapp_comment", id).get("status")).isEqualTo("hidden");
        owner().patch(detail(id), body("status", "published"));
        assertThat(row("myapp_comment", id).get("status")).isEqualTo("published");
    }

    @Test
    void theOwnerCanDeleteAComment() {
        long id = comment(post, "Visitor", "Nice one.");
        assertThat(owner().delete(detail(id)).status()).isEqualTo(204);
        assertThat(count("myapp_comment")).isZero();
    }

    @Test
    void aNonNumericIdIsA404() {
        assertThat(client.get("/api/comments/abc/").status()).isEqualTo(404);
    }

    @Test
    void deletingAPostTakesItsComments() {
        comment(post, "Visitor", "Nice one.");
        assertThat(owner().delete("/api/posts/" + post.slug() + "/").status()).isEqualTo(204);
        assertThat(count("myapp_comment")).isZero();
    }
}
