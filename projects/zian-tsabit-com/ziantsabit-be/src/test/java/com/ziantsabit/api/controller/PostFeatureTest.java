package com.ziantsabit.api.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ziantsabit.api.dto.ViewCount;
import com.ziantsabit.api.model.Post;
import com.ziantsabit.api.support.ApiTestBase;
import com.ziantsabit.api.support.Response;

/**
 * Ported from the Django suite's CoverImageTests, ViewCountTests, ViewDayTests,
 * TagTests, UpdatedOrderingTests and DateFilterTests.
 */
class PostFeatureTest extends ApiTestBase {

    static String detail(Post post) {
        return "/api/posts/" + post.slug() + "/";
    }

    static String view(Post post) {
        return "/api/posts/" + post.slug() + "/view/";
    }

    @Nested
    class CoverImage {

        @BeforeEach
        void fixtures() {
            createUser();
        }

        @Test
        void coverFieldsDefaultToBlank() {
            Post post = post("No Cover");
            assertThat(post.coverImageUrl()).isEmpty();
            assertThat(post.coverImageAlt()).isEmpty();
        }

        @Test
        void createWithACoverUrl() {
            Response response = owner().post("/api/posts/", body("title", "With Cover",
                    "cover_image_url", "http://localhost:9000/ziantsabit-media/a.png",
                    "cover_image_alt", "A blue square"));
            assertThat(response.status()).isEqualTo(201);
            assertThat(response.string("cover_image_url")).isEqualTo("http://localhost:9000/ziantsabit-media/a.png");
            assertThat(response.string("cover_image_alt")).isEqualTo("A blue square");
        }

        @Test
        void coverUrlMustBeAUrl() {
            Response response = owner().post("/api/posts/", body("title", "Bad", "cover_image_url", "not a url"));
            assertThat(response.status()).isEqualTo(400);
            assertThat(response.get("cover_image_url")).isEqualTo(List.of("Enter a valid URL."));
        }

        @Test
        void coverIsExposedToAnonymousReaders() {
            published("Public", p -> p.coverImageUrl = "http://localhost:9000/ziantsabit-media/b.png");
            assertThat(client.get("/api/posts/").results().get(0).get("cover_image_url"))
                    .isEqualTo("http://localhost:9000/ziantsabit-media/b.png");
        }

        @Test
        void coverCanBeCleared() {
            Post post = post("Clear Me", p -> p.coverImageUrl = "http://localhost:9000/ziantsabit-media/c.png");
            Response response = owner().patch(detail(post), body("cover_image_url", ""));
            assertThat(response.status()).isEqualTo(200);
            assertThat(response.string("cover_image_url")).isEmpty();
        }
    }

    @Nested
    class ViewCount {

        Post popular;
        Post quiet;
        Post draft;

        @BeforeEach
        void fixtures() {
            createUser();
            popular = published("Popular");
            quiet = published("Quiet");
            draft = post("Hidden");
        }

        @Test
        void newPostStartsAtZero() {
            assertThat(quiet.viewCount()).isZero();
        }

        @Test
        void anonymousCanRecordAView() {
            Response response = client.post(view(popular), null);
            assertThat(response.status()).isEqualTo(200);
            assertThat(response.map()).isEqualTo(Map.of("slug", popular.slug(), "view_count", 1));
        }

        @Test
        void viewsAccumulate() {
            for (int i = 0; i < 3; i++) {
                client.post(view(popular), null);
            }
            assertThat(row("myapp_post", popular.id()).get("view_count")).isEqualTo(3);
        }

        @Test
        void recordingAViewDoesNotTouchUpdatedAt() {
            Object before = row("myapp_post", popular.id()).get("updated_at");
            client.post(view(popular), null);
            assertThat(row("myapp_post", popular.id()).get("updated_at")).isEqualTo(before);
        }

        @Test
        void anonymousCannotRecordAViewOnADraft() {
            assertThat(client.post(view(draft), null).status()).isEqualTo(404);
            assertThat(row("myapp_post", draft.id()).get("view_count")).isEqualTo(0);
        }

        @Test
        void unknownSlugIs404() {
            assertThat(client.post("/api/posts/nothing-here/view/", null).status()).isEqualTo(404);
        }

        @Test
        void viewCountIsSerialised() {
            update("myapp_post", popular.id(), Map.of("view_count", 7));
            assertThat(client.get(detail(popular)).get("view_count")).isEqualTo(7);
        }

        @Test
        void viewCountIsReadOnlyOnAWrite() {
            update("myapp_post", popular.id(), Map.of("view_count", 7));
            Response response = owner().patch(detail(popular), body("view_count", 0));
            assertThat(response.status()).isEqualTo(200);
            assertThat(row("myapp_post", popular.id()).get("view_count")).isEqualTo(7);
        }

        @Test
        void orderingByViews() {
            update("myapp_post", quiet.id(), Map.of("view_count", 1));
            update("myapp_post", popular.id(), Map.of("view_count", 9));
            Response response = client.get("/api/posts/", "ordering", "views");
            assertThat(response.status()).isEqualTo(200);
            assertThat(response.slugs()).containsExactly(popular.slug(), quiet.slug());
        }

        @Test
        void defaultOrderingIsStillNewestFirst() {
            update("myapp_post", popular.id(), Map.of("view_count", 9));
            assertThat(client.get("/api/posts/").slugs()).containsExactly(quiet.slug(), popular.slug());
        }

        @Test
        void orderingCombinesWithATagFilter() {
            Post book = published("A Book", p -> p.tags = List.of("Books"));
            update("myapp_post", popular.id(), Map.of("view_count", 9));
            assertThat(client.get("/api/posts/", "ordering", "views", "tag", "books").slugs())
                    .containsExactly(book.slug());
        }

        @Test
        void unknownOrderingIsRejected() {
            Response response = client.get("/api/posts/", "ordering", "most-read");
            assertThat(response.status()).isEqualTo(400);
            assertThat(response.get("ordering")).isEqualTo(
                    List.of("'most-read' is not a valid ordering. Choose from: recent, updated, views."));
        }
    }

    @Nested
    class ViewDay {

        Post post;
        Post other;
        Post draft;

        @BeforeEach
        void fixtures() {
            post = published("Popular");
            other = published("Also Read");
            draft = post("Hidden");
        }

        List<Map<String, Object>> days(Post of) {
            return jdbc.sql("SELECT date, count FROM myapp_postviewday WHERE post_id = :id").param("id", of.id())
                    .query().listOfRows();
        }

        @Test
        void aReadIsFiledUnderToday() {
            client.post(view(post), null);
            List<Map<String, Object>> rows = days(post);
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).get("date").toString()).isEqualTo(today().toString());
            assertThat(rows.get(0).get("count")).isEqualTo(1);
        }

        @Test
        void furtherReadsTheSameDayRaiseOneRow() {
            for (int i = 0; i < 4; i++) {
                client.post(view(post), null);
            }
            assertThat(days(post)).hasSize(1);
            assertThat(days(post).get(0).get("count")).isEqualTo(4);
        }

        @Test
        void eachPostKeepsItsOwnRow() {
            client.post(view(post), null);
            client.post(view(other), null);
            assertThat(count("myapp_postviewday")).isEqualTo(2);
        }

        @Test
        void theDailyRowMatchesTheCounter() {
            for (int i = 0; i < 3; i++) {
                client.post(view(post), null);
            }
            assertThat(row("myapp_post", post.id()).get("view_count")).isEqualTo(days(post).get(0).get("count"));
        }

        @Test
        void aRefusedReadRecordsNothing() {
            client.post(view(draft), null);
            assertThat(days(draft)).isEmpty();
        }

        @Test
        void deletingAPostTakesItsHistoryWithIt() {
            createUser();
            client.post(view(post), null);
            assertThat(owner().delete(detail(post)).status()).isEqualTo(204);
            assertThat(count("myapp_postviewday")).isZero();
        }
    }

    @Nested
    class Tags {

        @BeforeEach
        void fixtures() {
            createUser();
        }

        @Test
        void aPostStartsWithNoTags() {
            assertThat(post("Untagged").tags()).isEmpty();
        }

        @Test
        void tagsAreTrimmedAndBlanksDropped() {
            Post post = post("Tagged", p -> p.tags = List.of("  django ", "", "   ", "postgres"));
            assertThat(post.tags()).containsExactly("django", "postgres");
        }

        @Test
        void repeatsAreDroppedCaseInsensitivelyKeepingTheFirstSpelling() {
            assertThat(post("Repeats", p -> p.tags = List.of("Django", "django", "DJANGO")).tags())
                    .containsExactly("Django");
        }

        @Test
        void orderIsPreserved() {
            assertThat(post("Ordered", p -> p.tags = List.of("zebra", "apple", "mango")).tags())
                    .containsExactly("zebra", "apple", "mango");
        }

        @Test
        void tagsAreSerialised() {
            Post post = published("Serialised", p -> p.tags = List.of("django", "rest"));
            Response response = client.get(detail(post));
            assertThat(response.status()).isEqualTo(200);
            assertThat(response.get("tags")).isEqualTo(List.of("django", "rest"));
        }

        @Test
        void createWithTags() {
            Response response = owner().post("/api/posts/",
                    body("title", "Created", "tags", List.of("Django", " django ", "postgres")));
            assertThat(response.status()).isEqualTo(201);
            assertThat(response.get("tags")).isEqualTo(List.of("Django", "postgres"));
        }

        @Test
        void patchReplacesTheWholeList() {
            Post post = post("Replaced", p -> p.tags = List.of("one", "two"));
            assertThat(owner().patch(detail(post), body("tags", List.of("three"))).status()).isEqualTo(200);
            assertThat(tagsOf(post.id())).containsExactly("three");
        }

        @Test
        void anUnrelatedPatchLeavesTagsAlone() {
            Post post = post("Kept", p -> p.tags = List.of("one", "two"));
            assertThat(owner().patch(detail(post), body("title", "Kept, renamed")).status()).isEqualTo(200);
            assertThat(tagsOf(post.id())).containsExactly("one", "two");
        }

        @Test
        void tagsCanBeCleared() {
            Post post = post("Cleared", p -> p.tags = List.of("one"));
            assertThat(owner().patch(detail(post), body("tags", List.of())).status()).isEqualTo(200);
            assertThat(tagsOf(post.id())).isEmpty();
        }

        @Test
        void anOverlongTagIsRejected() {
            Response response = owner().post("/api/posts/", body("title", "Too long", "tags", List.of("x".repeat(51))));
            assertThat(response.status()).isEqualTo(400);
            assertThat(response.get("tags"))
                    .isEqualTo(Map.of("0", List.of("Ensure this field has no more than 50 characters.")));
        }

        @Test
        void aTagListThatIsNotAListIsRejected() {
            Response response = owner().post("/api/posts/", body("title", "X", "tags", "django"));
            assertThat(response.status()).isEqualTo(400);
            assertThat(response.get("tags")).isEqualTo(List.of("Expected a list of items but got type \"str\"."));
        }

        @Test
        void anonymousCallersCannotWriteTags() {
            Post post = post("Guarded");
            assertThat(client.patch(detail(post), body("tags", List.of("sneaky"))).status()).isEqualTo(403);
            assertThat(tagsOf(post.id())).isEmpty();
        }
    }

    @Nested
    class UpdatedOrdering {

        Post revised;
        Post newer;

        @BeforeEach
        void fixtures() {
            createUser();
            Instant now = Instant.now();
            // Published first, but edited most recently: the two orderings
            // disagree about it, which is the whole point of the option.
            revised = published("Revised");
            newer = published("Newer");
            update("myapp_post", revised.id(), Map.of("published_at", now.minus(30, ChronoUnit.DAYS),
                    "updated_at", now.minus(1, ChronoUnit.MINUTES)));
            update("myapp_post", newer.id(), Map.of("published_at", now.minus(1, ChronoUnit.DAYS),
                    "updated_at", now.minus(1, ChronoUnit.DAYS)));
        }

        List<Object> slugs(String... params) {
            Response response = client.get("/api/posts/", params);
            assertThat(response.status()).isEqualTo(200);
            return response.slugs();
        }

        @Test
        void mostRecentlyEditedComesFirst() {
            assertThat(slugs("ordering", "updated")).containsExactly(revised.slug(), newer.slug());
        }

        @Test
        void itDiffersFromTheDefaultOrdering() {
            assertThat(slugs()).containsExactly(newer.slug(), revised.slug());
        }

        @Test
        void editingAPostMovesItToTheFront() {
            assertThat(owner().patch(detail(newer), body("title", "Newer, revised")).status()).isEqualTo(200);
            assertThat(slugs("ordering", "updated")).containsExactly(newer.slug(), revised.slug());
        }

        @Test
        void recordingAViewDoesNotReorder() {
            client.post(view(newer), null);
            assertThat(slugs("ordering", "updated")).containsExactly(revised.slug(), newer.slug());
        }

        @Test
        void draftsStayHiddenFromAnonymousCallers() {
            Post draft = post("Hidden");
            assertThat(slugs("ordering", "updated")).doesNotContain(draft.slug());
        }

        @Test
        void itCombinesWithATagFilter() {
            Post book = published("A Book", p -> p.tags = List.of("Books"));
            assertThat(slugs("ordering", "updated", "tag", "books")).containsExactly(book.slug());
        }
    }

    @Nested
    class DateFilter {

        Post old;
        Post middle;
        Post recent;
        Post draft;

        Post on(String title, String day) {
            Post post = published(title);
            update("myapp_post", post.id(), Map.of("published_at", Instant.parse(day + "T12:00:00Z")));
            return post;
        }

        @BeforeEach
        void fixtures() {
            createUser();
            old = on("Old", "2026-01-10");
            middle = on("Middle", "2026-05-20");
            recent = on("Recent", "2026-08-01");
            // No published_at: filtered by created_at instead.
            draft = post("Draft");
        }

        @Test
        void afterIsInclusiveOfItsOwnDay() {
            Response response = client.get("/api/posts/", "published_after", "2026-05-20");
            assertThat(response.status()).isEqualTo(200);
            assertThat(response.slugSet()).isEqualTo(Set.of(middle.slug(), recent.slug()));
        }

        @Test
        void beforeIsInclusiveOfItsOwnDay() {
            assertThat(client.get("/api/posts/", "published_before", "2026-05-20").slugSet())
                    .isEqualTo(Set.of(old.slug(), middle.slug()));
        }

        @Test
        void bothEndsTogetherAreARange() {
            assertThat(client.get("/api/posts/", "published_after", "2026-02-01", "published_before", "2026-07-01")
                    .slugSet()).isEqualTo(Set.of(middle.slug()));
        }

        @Test
        void rangeWithNothingInItIsEmptyNotAnError() {
            Response response = client.get("/api/posts/", "published_after", "2026-09-01",
                    "published_before", "2026-09-30");
            assertThat(response.status()).isEqualTo(200);
            assertThat(response.results()).isEmpty();
        }

        @Test
        void noDateParamsReturnsEverythingPublished() {
            assertThat(client.get("/api/posts/").results()).hasSize(3);
        }

        @Test
        void draftIsFilteredByCreatedAt() {
            assertThat(owner().get("/api/posts/", "published_after", today().toString()).slugSet())
                    .contains(draft.slug());
        }

        @Test
        void draftOutsideTheRangeDropsOut() {
            assertThat(owner().get("/api/posts/", "published_before", "2026-01-01").slugSet())
                    .doesNotContain(draft.slug());
        }

        @Test
        void dateFilterCombinesWithTag() {
            Post book = resave(on("A Book", "2026-05-21"), p -> p.tags = List.of("Books"));
            assertThat(client.get("/api/posts/", "tag", "books", "published_after", "2026-05-01").slugSet())
                    .isEqualTo(Set.of(book.slug()));
        }

        @Test
        void malformedDateIsRejected() {
            Response response = client.get("/api/posts/", "published_after", "10-05-2026");
            assertThat(response.status()).isEqualTo(400);
            assertThat(response.get("published_after"))
                    .isEqualTo(List.of("'10-05-2026' is not a valid date. Use YYYY-MM-DD."));
        }

        @Test
        void impossibleDateIsRejected() {
            Response response = client.get("/api/posts/", "published_before", "2026-02-31");
            assertThat(response.status()).isEqualTo(400);
            assertThat(response.map()).containsKey("published_before");
        }

        @Test
        void emptyDateParamIsIgnored() {
            Response response = client.get("/api/posts/", "published_after", "");
            assertThat(response.status()).isEqualTo(200);
            assertThat(response.results()).hasSize(3);
        }
    }
}
