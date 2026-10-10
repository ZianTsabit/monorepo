package com.ziantsabit.api.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ziantsabit.api.model.Post;
import com.ziantsabit.api.support.Api;
import com.ziantsabit.api.support.ApiTestBase;
import com.ziantsabit.api.support.Response;

/** Ported from the Django suite's PostModelTests and PostAPITests. */
class PostApiTest extends ApiTestBase {

    @Nested
    class Model {

        @Test
        void slugIsDerivedFromTitle() {
            assertThat(post("Clean Code").slug()).isEqualTo("clean-code");
        }

        @Test
        void duplicateTitlesGetDistinctSlugs() {
            assertThat(List.of(post("Clean Code").slug(), post("Clean Code").slug(), post("Clean Code").slug()))
                    .containsExactly("clean-code", "clean-code-2", "clean-code-3");
        }

        @Test
        void explicitSlugIsKept() {
            assertThat(post("Clean Code", p -> p.slug = "the-one").slug()).isEqualTo("the-one");
        }

        @Test
        void publishingStampsPublishedAt() {
            assertThat(published("Shipped").publishedAt()).isNotNull();
        }

        @Test
        void draftHasNoPublishedAt() {
            assertThat(post("Draft").publishedAt()).isNull();
        }

        @Test
        void titleOfOnlyPunctuationStillGetsASlug() {
            assertThat(post("!!!").slug()).isEqualTo("post");
        }

        @Test
        void slugifyMatchesDjangos() {
            assertThat(com.ziantsabit.api.util.Slugs.slugify("  Héllo, Wörld!  -- _Ok_ "))
                    .isEqualTo("hello-world-_ok");
        }
    }

    @Nested
    class Api {

        Post publishedBook;
        Post draft;
        Post sale;

        @BeforeEach
        void fixtures() {
            createUser();
            publishedBook = published("Published Book");
            draft = post("Draft Project");
            sale = published("Old Desk", p -> p.tags = List.of("Furniture"));
        }

        String detail(Post post) {
            return "/api/posts/" + post.slug() + "/";
        }

        // --- read ---------------------------------------------------------

        @Test
        void anonymousListShowsOnlyPublished() {
            Response response = client.get("/api/posts/");
            assertThat(response.status()).isEqualTo(200);
            assertThat(response.slugSet()).isEqualTo(Set.of(publishedBook.slug(), sale.slug()));
        }

        @Test
        void listIsPaginatedInDrfsEnvelope() {
            Response response = client.get("/api/posts/");
            assertThat(response.map()).containsKeys("count", "next", "previous", "results");
            assertThat(response.get("count")).isEqualTo(2);
            assertThat(response.get("next")).isNull();
        }

        @Test
        void aPostIsSerialisedWithEveryField() {
            Map<String, Object> row = client.get(detail(sale)).map();
            assertThat(row.keySet()).containsExactly("id", "title", "slug", "excerpt", "body", "cover_image_url",
                    "cover_image_alt", "tags", "status", "published_at", "comments_enabled", "reactions_enabled",
                    "theme", "view_count", "comment_count", "created_at", "updated_at");
            assertThat((String) row.get("published_at")).endsWith("Z");
        }

        @Test
        void anonymousCannotRetrieveADraftBySlug() {
            Response response = client.get(detail(draft));
            assertThat(response.status()).isEqualTo(404);
            assertThat(response.string("detail")).isEqualTo("No Post matches the given query.");
        }

        @Test
        void authenticatedListIncludesDrafts() {
            assertThat(owner().get("/api/posts/").results()).hasSize(3);
        }

        @Test
        void filterByTag() {
            Response response = client.get("/api/posts/", "tag", "Furniture");
            assertThat(response.status()).isEqualTo(200);
            assertThat(response.slugs()).containsExactly("old-desk");
        }

        @Test
        void filterByTagIgnoresCase() {
            assertThat(client.get("/api/posts/", "tag", "fUrNiTuRe").slugs()).containsExactly("old-desk");
        }

        @Test
        void filterByTagMatchesASecondSpellingOnAnotherPost() {
            Post other = published("Another Desk", p -> p.tags = List.of("furniture"));
            assertThat(client.get("/api/posts/", "tag", "Furniture").slugSet())
                    .isEqualTo(Set.of("old-desk", other.slug()));
        }

        @Test
        void unusedTagReturnsNothingRatherThanErroring() {
            Response response = client.get("/api/posts/", "tag", "recipes");
            assertThat(response.status()).isEqualTo(200);
            assertThat(response.results()).isEmpty();
        }

        @Test
        void tagFilterDoesNotLeakDrafts() {
            post("Secret", p -> p.tags = List.of("Furniture"));
            assertThat(client.get("/api/posts/", "tag", "Furniture").slugs()).containsExactly("old-desk");
        }

        @Test
        void aPostWithTwoTagsIsReturnedByBoth() {
            Post both = published("Building a Bookshelf", p -> p.tags = List.of("Books", "Projects"));
            for (String label : List.of("books", "projects")) {
                assertThat(client.get("/api/posts/", "tag", label).slugs()).contains(both.slug());
            }
        }

        @Test
        void aPostAppearsOnceInAnUnfilteredList() {
            published("Building a Bookshelf", p -> p.tags = List.of("Books", "Projects"));
            List<Object> slugs = client.get("/api/posts/").slugs();
            assertThat(slugs).doesNotHaveDuplicates();
        }

        @Test
        void filterByStatusWhenAuthenticated() {
            assertThat(owner().get("/api/posts/", "status", "draft").slugs()).containsExactly("draft-project");
        }

        @Test
        void unknownStatusIsRejectedForTheOwner() {
            Response response = owner().get("/api/posts/", "status", "pubished");
            assertThat(response.status()).isEqualTo(400);
            assertThat(response.map()).containsKey("status");
        }

        @Test
        void retrieveBySlug() {
            Response response = client.get(detail(publishedBook));
            assertThat(response.status()).isEqualTo(200);
            assertThat(response.string("title")).isEqualTo("Published Book");
        }

        @Test
        void routesWorkWithoutTheTrailingSlashToo() {
            assertThat(client.get("/api/posts").status()).isEqualTo(200);
            assertThat(client.get("/api/posts/" + publishedBook.slug()).status()).isEqualTo(200);
        }

        // --- write --------------------------------------------------------

        @Test
        void anonymousCannotCreate() {
            Response response = client.post("/api/posts/", body("title", "Nope"));
            assertThat(response.status()).isEqualTo(403);
            assertThat(response.string("detail")).isEqualTo("Authentication credentials were not provided.");
            assertThat(count("myapp_post")).isEqualTo(3);
        }

        @Test
        void createGeneratesSlugAndDefaultsToDraft() {
            Response response = owner().post("/api/posts/", body("title", "A New Post", "body", "hello"));
            assertThat(response.status()).isEqualTo(201);
            assertThat(response.string("slug")).isEqualTo("a-new-post");
            assertThat(response.string("status")).isEqualTo("draft");
        }

        @Test
        void basicAuthCanCreate() {
            assertThat(owner().post("/api/posts/", body("title", "Via Curl")).status()).isEqualTo(201);
        }

        @Test
        void wrongBasicCredentialsAreA403() {
            Response response = anonymous().basicAuth(USERNAME, "wrong").post("/api/posts/", body("title", "X"));
            assertThat(response.status()).isEqualTo(403);
            assertThat(response.string("detail")).isEqualTo("Invalid username/password.");
            assertThat(response.header("WWW-Authenticate")).isNull();
        }

        @Test
        void createAcceptsAnyTag() {
            Response response = owner().post("/api/posts/", body("title", "X", "tags", List.of("recipes")));
            assertThat(response.status()).isEqualTo(201);
            assertThat(response.get("tags")).isEqualTo(List.of("recipes"));
        }

        @Test
        void createAcceptsAnUntaggedPost() {
            for (Map<String, Object> request : List.of(body("title", "Bare", "tags", List.of()),
                    body("title", "Barer"))) {
                Response response = owner().post("/api/posts/", request);
                assertThat(response.status()).isEqualTo(201);
                assertThat(response.get("tags")).isEqualTo(List.of());
            }
        }

        @Test
        void createRejectsBlankSlug() {
            Response response = owner().post("/api/posts/", body("title", "X", "slug", "   "));
            assertThat(response.status()).isEqualTo(400);
            assertThat(response.map()).containsKey("slug");
        }

        @Test
        void createRejectsATakenSlug() {
            Response response = owner().post("/api/posts/", body("title", "X", "slug", "old-desk"));
            assertThat(response.status()).isEqualTo(400);
            assertThat(response.get("slug")).isEqualTo(List.of("post with this slug already exists."));
        }

        @Test
        void createRequiresATitle() {
            Response response = owner().post("/api/posts/", body("body", "no title"));
            assertThat(response.status()).isEqualTo(400);
            assertThat(response.get("title")).isEqualTo(List.of("This field is required."));
        }

        @Test
        void aBodyThatIsNotAnObjectIsRefused() {
            Response response = owner().post("/api/posts/", List.of(1, 2));
            assertThat(response.status()).isEqualTo(400);
            assertThat(response.get("non_field_errors"))
                    .isEqualTo(List.of("Invalid data. Expected a dictionary, but got list."));
        }

        @Test
        void patchUpdatesAField() {
            Response response = owner().patch(detail(publishedBook), body("excerpt", "short"));
            assertThat(response.status()).isEqualTo(200);
            assertThat(row("myapp_post", publishedBook.id()).get("excerpt")).isEqualTo("short");
        }

        @Test
        void patchPublishingADraftStampsPublishedAt() {
            Response response = owner().patch(detail(draft), body("status", "published"));
            assertThat(response.status()).isEqualTo(200);
            assertThat(row("myapp_post", draft.id()).get("published_at")).isNotNull();
        }

        @Test
        void putReplacesThePost() {
            Map<String, Object> request = body("title", "Renamed", "slug", publishedBook.slug(), "excerpt", "",
                    "body", "", "status", "published", "published_at", null);
            Response response = owner().put(detail(publishedBook), request);
            assertThat(response.status()).isEqualTo(200);
            assertThat(row("myapp_post", publishedBook.id()).get("title")).isEqualTo("Renamed");
        }

        @Test
        void putWithoutATitleIsRefused() {
            Response response = owner().put(detail(publishedBook), body("excerpt", "x"));
            assertThat(response.status()).isEqualTo(400);
            assertThat(response.map()).containsKey("title");
        }

        @Test
        void allowedOriginGetsCorsHeader() {
            Response response = client.get("/api/posts/", Map.of("Origin", SPA_ORIGIN));
            assertThat(response.header("Access-Control-Allow-Origin")).isEqualTo(SPA_ORIGIN);
            assertThat(response.header("Access-Control-Allow-Credentials")).isEqualTo("true");
        }

        @Test
        void preflightIsAnswered() {
            Response response = client.options("/api/posts/", Map.of("Origin", SPA_ORIGIN,
                    "Access-Control-Request-Method", "PATCH",
                    "Access-Control-Request-Headers", "content-type, x-csrftoken"));
            assertThat(response.status()).isEqualTo(200);
            assertThat(response.header("Access-Control-Allow-Headers")).isNotNull();
            assertThat(response.header("Access-Control-Allow-Origin")).isEqualTo(SPA_ORIGIN);
        }

        @Test
        void unknownOriginGetsNoCorsHeader() {
            Response response = client.get("/api/posts/", Map.of("Origin", "http://evil.example"));
            assertThat(response.header("Access-Control-Allow-Origin")).isNull();
        }

        @Test
        void deleteRemovesThePost() {
            Response response = owner().delete(detail(sale));
            assertThat(response.status()).isEqualTo(204);
            assertThat(exists("myapp_post", sale.id())).isFalse();
        }

        @Test
        void anonymousCannotDelete() {
            assertThat(client.delete(detail(sale)).status()).isEqualTo(403);
            assertThat(exists("myapp_post", sale.id())).isTrue();
        }
    }
}
