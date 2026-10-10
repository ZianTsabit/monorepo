package com.ziantsabit.api.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ziantsabit.api.model.Post;
import com.ziantsabit.api.model.Reaction;
import com.ziantsabit.api.support.ApiTestBase;
import com.ziantsabit.api.support.Response;

/**
 * Ported from the Django suite's PostThemeTests, VisitorSwitchTests,
 * TagVocabularyTests, CommentCountTests and ReactionTests.
 */
class PostVisitorTest extends ApiTestBase {

    static final String EMOJI = Reaction.EMOJI_VALUES.get(0);
    static final String OTHER_EMOJI = Reaction.EMOJI_VALUES.get(1);
    static final String RETIRED = Character.toString(0x1F4A9);

    static String detail(Post post) {
        return "/api/posts/" + post.slug() + "/";
    }

    static String reactions(Post post) {
        return "/api/posts/" + post.slug() + "/reactions/";
    }

    @Nested
    class Theme {

        Post stormy;
        Post plain;

        @BeforeEach
        void fixtures() {
            createUser();
            stormy = published("A Night Of Rain", p -> p.theme = "rain");
            plain = published("An Ordinary Post");
        }

        @Test
        void aPostDefaultsToTheReadersOwnTheme() {
            assertThat(plain.theme()).isEmpty();
        }

        @Test
        void aPostCreatedThroughTheApiDefaultsToBlank() {
            Response response = owner().post("/api/posts/", body("title", "Fresh"));
            assertThat(response.status()).isEqualTo(201);
            assertThat(response.string("theme")).isEmpty();
        }

        @Test
        void theThemeIsSerialisedForAnonymousReaders() {
            Response response = client.get(detail(stormy));
            assertThat(response.status()).isEqualTo(200);
            assertThat(response.string("theme")).isEqualTo("rain");
        }

        @Test
        void everySchemeIsAccepted() {
            for (String scheme : List.of("light", "dark", "rain")) {
                Response response = owner().patch(detail(plain), body("theme", scheme));
                assertThat(response.status()).isEqualTo(200);
                assertThat(response.string("theme")).isEqualTo(scheme);
            }
        }

        @Test
        void aSchemeTheSiteDoesNotHaveIsRejected() {
            Response response = owner().patch(detail(plain), body("theme", "sepia"));
            assertThat(response.status()).isEqualTo(400);
            assertThat(response.get("theme")).isEqualTo(List.of("\"sepia\" is not a valid choice."));
        }

        @Test
        void clearingTheThemeGivesTheReaderTheirChoiceBack() {
            Response response = owner().patch(detail(stormy), body("theme", ""));
            assertThat(response.status()).isEqualTo(200);
            assertThat(response.string("theme")).isEmpty();
        }

        @Test
        void anAutosaveThatOmitsTheThemeKeepsIt() {
            Response response = owner().patch(detail(stormy), body("body", "Still raining."));
            assertThat(response.status()).isEqualTo(200);
            assertThat(response.string("theme")).isEqualTo("rain");
        }

        @Test
        void anonymousCallersCannotSetATheme() {
            assertThat(client.patch(detail(plain), body("theme", "rain")).status()).isEqualTo(403);
        }
    }

    @Nested
    class VisitorSwitches {

        Post open;
        Post closed;

        @BeforeEach
        void fixtures() {
            createUser();
            open = published("Open House");
            closed = published("Closed Thread", p -> {
                p.commentsEnabled = false;
                p.reactionsEnabled = false;
            });
        }

        Map<String, Object> commentBody(Post post) {
            return body("post", post.slug(), "author_name", "Ada", "body", "Hello.");
        }

        @Test
        void bothDefaultToOn() {
            Post post = post("Fresh");
            assertThat(post.commentsEnabled()).isTrue();
            assertThat(post.reactionsEnabled()).isTrue();
        }

        @Test
        void aPostCreatedThroughTheApiDefaultsToOn() {
            Response response = owner().post("/api/posts/", body("title", "Fresh"));
            assertThat(response.status()).isEqualTo(201);
            assertThat(response.get("comments_enabled")).isEqualTo(true);
            assertThat(response.get("reactions_enabled")).isEqualTo(true);
        }

        @Test
        void bothAreSerialised() {
            Response response = client.get(detail(closed));
            assertThat(response.get("comments_enabled")).isEqualTo(false);
            assertThat(response.get("reactions_enabled")).isEqualTo(false);
        }

        @Test
        void aVisitorMayCommentOnAnOpenPost() {
            assertThat(client.post("/api/comments/", commentBody(open)).status()).isEqualTo(201);
        }

        @Test
        void aVisitorMayNotCommentOnAClosedPost() {
            assertThat(client.post("/api/comments/", commentBody(closed)).status()).isEqualTo(400);
            assertThat(count("myapp_comment")).isZero();
        }

        @Test
        void aClosedThreadSaysSoRatherThanPlayingDead() {
            Response response = client.post("/api/comments/", commentBody(closed));
            assertThat(response.get("post")).isEqualTo(List.of("Comments are closed on this post."));
        }

        @Test
        void theOwnerMayStillCommentOnAClosedPost() {
            assertThat(owner().post("/api/comments/", commentBody(closed)).status()).isEqualTo(201);
        }

        @Test
        void closingAThreadLeavesItsCommentsReadable() {
            comment(closed, "Ada", "Said before it shut.");
            assertThat(client.get("/api/comments/", "post", closed.slug()).column("body"))
                    .containsExactly("Said before it shut.");
        }

        @Test
        void aClosedPostStillReportsItsCommentCount() {
            comment(closed, "Ada", "Hi.");
            assertThat(client.get(detail(closed)).get("comment_count")).isEqualTo(1);
        }

        @Test
        void aVisitorMayReactToAnOpenPost() {
            assertThat(client.post(reactions(open), body("emoji", EMOJI, "visitor", "abc")).status())
                    .isEqualTo(200);
        }

        @Test
        void aVisitorMayNotReactToAPostWithReactionsOff() {
            Response response = client.post(reactions(closed), body("emoji", EMOJI, "visitor", "abc"));
            assertThat(response.status()).isEqualTo(400);
            assertThat(response.get("emoji")).isEqualTo(List.of("Reactions are turned off for this post."));
            assertThat(count("myapp_reaction")).isZero();
        }

        @Test
        void theOwnerMayStillReactToAPostWithReactionsOff() {
            assertThat(owner().post(reactions(closed), body("emoji", EMOJI, "visitor", "abc")).status())
                    .isEqualTo(200);
        }

        @Test
        void readingTheBarStillWorksWithReactionsOff() {
            Response response = client.get(reactions(closed));
            assertThat(response.status()).isEqualTo(200);
            assertThat((List<?>) response.get("reactions")).hasSize(Reaction.EMOJI.size());
        }

        @Test
        void turningReactionsBackOnBringsTheCountsWithIt() {
            reaction(closed, EMOJI, "abc");
            resave(closed, p -> p.reactionsEnabled = true);
            assertThat(client.get(reactions(closed)).get("total")).isEqualTo(1);
        }

        @Test
        void theOwnerCanCloseAThreadWithAPatch() {
            assertThat(owner().patch(detail(open), body("comments_enabled", false)).status()).isEqualTo(200);
            Map<String, Object> row = row("myapp_post", open.id());
            assertThat(row.get("comments_enabled")).isEqualTo(false);
            assertThat(row.get("reactions_enabled")).isEqualTo(true);
        }

        @Test
        void aVisitorCannotReopenAThread() {
            assertThat(client.patch(detail(closed), body("comments_enabled", true)).status()).isEqualTo(403);
            assertThat(row("myapp_post", closed.id()).get("comments_enabled")).isEqualTo(false);
        }
    }

    @Nested
    class TagVocabulary {

        @BeforeEach
        void fixtures() {
            createUser();
            published("First", p -> p.tags = List.of("Django", "Postgres"));
            published("Second", p -> p.tags = List.of("django"));
            post("Draft", p -> p.tags = List.of("Unpublished Thing"));
        }

        Map<Object, Object> counts(Response response) {
            return response.list().stream().collect(Collectors.toMap(r -> r.get("name"), r -> r.get("count")));
        }

        @Test
        void tagsAreCountedAcrossPosts() {
            Response response = client.get("/api/posts/tags/");
            assertThat(response.status()).isEqualTo(200);
            assertThat(counts(response).get("Postgres")).isEqualTo(1);
        }

        @Test
        void spellingsAreFoldedIntoOneOption() {
            Map<Object, Object> counts = counts(client.get("/api/posts/tags/"));
            assertThat(counts.get("Django")).isEqualTo(2);
            assertThat(counts).doesNotContainKey("django");
        }

        @Test
        void theCommonestSpellingWins() {
            for (String title : List.of("Third", "Fourth")) {
                published(title, p -> p.tags = List.of("postgres"));
            }
            Map<Object, Object> counts = counts(client.get("/api/posts/tags/"));
            assertThat(counts.get("postgres")).isEqualTo(3);
            assertThat(counts).doesNotContainKey("Postgres");
        }

        @Test
        void orderedCommonestFirstThenAlphabetically() {
            assertThat(client.get("/api/posts/tags/").list().stream().map(r -> r.get("name")).toList())
                    .containsExactly("Django", "Postgres");
        }

        @Test
        void aDraftsTagsAreNotOfferedToAnonymousCallers() {
            assertThat(counts(client.get("/api/posts/tags/"))).doesNotContainKey("Unpublished Thing");
        }

        @Test
        void aDraftsTagsAreOfferedToTheOwner() {
            assertThat(counts(owner().get("/api/posts/tags/"))).containsKey("Unpublished Thing");
        }

        @Test
        void anUntaggedSiteReportsAnEmptyList() {
            jdbc.sql("DELETE FROM myapp_post").update();
            Response response = client.get("/api/posts/tags/");
            assertThat(response.status()).isEqualTo(200);
            assertThat(response.list()).isEmpty();
        }
    }

    @Nested
    class CommentCount {

        Post post;

        @BeforeEach
        void fixtures() {
            createUser();
            post = published("Counted");
        }

        @Test
        void aPostWithNoCommentsReportsZero() {
            assertThat(client.get(detail(post)).get("comment_count")).isEqualTo(0);
        }

        @Test
        void commentsAreCounted() {
            for (int index = 0; index < 3; index++) {
                comment(post, "Ada", "#" + index);
            }
            assertThat(client.get(detail(post)).get("comment_count")).isEqualTo(3);
        }

        @Test
        void hiddenCommentsAreNotCountedEvenForTheOwner() {
            comment(post, "Ada", "Fine");
            comment(post, "Bot", "Spam", "hidden");
            assertThat(owner().get(detail(post)).get("comment_count")).isEqualTo(1);
        }

        @Test
        void aPostWithNoCommentsStillAppearsInTheFeed() {
            published("Silent");
            assertThat(client.get("/api/posts/").get("count")).isEqualTo(2);
        }

        @Test
        void theCountSurvivesAWrite() {
            Response response = owner().post("/api/posts/", body("title", "Fresh"));
            assertThat(response.status()).isEqualTo(201);
            assertThat(response.get("comment_count")).isEqualTo(0);
        }

        @Test
        void theFeedIsStillNewestFirst() {
            Post older = published("Older");
            Post newer = published("Newer");
            assertThat(client.get("/api/posts/").slugs().subList(0, 2)).containsExactly(newer.slug(), older.slug());
        }
    }

    @Nested
    class ReactionBar {

        Post post;
        Post draft;

        @BeforeEach
        void fixtures() {
            post = published("Reacted To");
            draft = post("Unpublished");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> counts(Response response) {
            Map<String, Object> counts = new HashMap<>();
            for (Map<String, Object> row : (List<Map<String, Object>>) response.get("reactions")) {
                counts.put((String) row.get("emoji"), row.get("count"));
            }
            return counts;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> reacted(Response response) {
            Map<String, Object> reacted = new HashMap<>();
            for (Map<String, Object> row : (List<Map<String, Object>>) response.get("reactions")) {
                reacted.put((String) row.get("emoji"), row.get("reacted"));
            }
            return reacted;
        }

        Response react(String emoji, String visitor) {
            return client.post(reactions(post), body("emoji", emoji, "visitor", visitor));
        }

        @Test
        @SuppressWarnings("unchecked")
        void theBarIsDense() {
            Response response = client.get(reactions(post));
            assertThat(response.status()).isEqualTo(200);
            List<Map<String, Object>> rows = (List<Map<String, Object>>) response.get("reactions");
            assertThat(rows.stream().map(r -> r.get("emoji")).toList()).isEqualTo(Reaction.EMOJI_VALUES);
            assertThat(rows.stream().map(r -> r.get("count")).distinct().toList()).containsExactly(0);
            assertThat(response.map().keySet()).containsExactly("slug", "total", "reactions");
            assertThat(rows.get(0).keySet()).containsExactly("emoji", "label", "count", "reacted");
        }

        @Test
        @SuppressWarnings("unchecked")
        void everyButtonCarriesALabel() {
            List<Map<String, Object>> rows = (List<Map<String, Object>>) client.get(reactions(post)).get("reactions");
            assertThat(rows).allMatch(row -> !((String) row.get("label")).isEmpty());
        }

        @Test
        void anonymousCanReact() {
            Response response = react(EMOJI, "abc");
            assertThat(response.status()).isEqualTo(200);
            assertThat(counts(response).get(EMOJI)).isEqualTo(1);
            assertThat(response.get("total")).isEqualTo(1);
        }

        @Test
        void reactingTwiceTakesItBack() {
            react(EMOJI, "abc");
            Response response = react(EMOJI, "abc");
            assertThat(counts(response).get(EMOJI)).isEqualTo(0);
            assertThat(count("myapp_reaction")).isZero();
        }

        @Test
        void oneVisitorMayLeaveSeveralDifferentEmoji() {
            react(EMOJI, "abc");
            assertThat(react(OTHER_EMOJI, "abc").get("total")).isEqualTo(2);
        }

        @Test
        void twoVisitorsAreCountedSeparately() {
            react(EMOJI, "abc");
            assertThat(counts(react(EMOJI, "xyz")).get(EMOJI)).isEqualTo(2);
        }

        @Test
        void reactedIsReportedForTheTokenThatAsked() {
            react(EMOJI, "abc");
            assertThat(reacted(client.get(reactions(post), "visitor", "abc")).get(EMOJI)).isEqualTo(true);
            assertThat(reacted(client.get(reactions(post), "visitor", "xyz")).get(EMOJI)).isEqualTo(false);
        }

        @Test
        void withoutATokenNothingIsReacted() {
            react(EMOJI, "abc");
            Response response = client.get(reactions(post));
            assertThat(reacted(response).values()).containsOnly(false);
            assertThat(counts(response).get(EMOJI)).isEqualTo(1);
        }

        @Test
        void anEmojiOffTheListIsRejected() {
            Response response = react(RETIRED, "abc");
            assertThat(response.status()).isEqualTo(400);
            assertThat(count("myapp_reaction")).isZero();
        }

        @Test
        void aBlankVisitorTokenIsRejected() {
            assertThat(react(EMOJI, "  ").status()).isEqualTo(400);
        }

        @Test
        void aDraftIsA404ToAVisitor() {
            assertThat(client.get(reactions(draft)).status()).isEqualTo(404);
            assertThat(client.post(reactions(draft), body("emoji", EMOJI, "visitor", "abc")).status())
                    .isEqualTo(404);
        }

        @Test
        void aRetiredEmojiIsNeitherShownNorTotalled() {
            reaction(post, RETIRED, "abc");
            Response response = client.get(reactions(post));
            assertThat(response.get("total")).isEqualTo(0);
            assertThat(counts(response)).doesNotContainKey(RETIRED);
        }

        @Test
        void deletingAPostTakesItsReactions() {
            createUser();
            reaction(post, EMOJI, "abc");
            assertThat(owner().delete(detail(post)).status()).isEqualTo(204);
            assertThat(count("myapp_reaction")).isZero();
        }
    }
}
