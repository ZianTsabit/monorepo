package com.ziantsabit.api.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziantsabit.api.model.Post;
import com.ziantsabit.api.service.PostStatsService;
import com.ziantsabit.api.support.ApiTestBase;
import com.ziantsabit.api.support.Response;

/** GET /api/posts/stats/. Ported from the Django suite's PostStatsTests. */
class PostStatsTest extends ApiTestBase {

    Post top;
    Post draft;

    Post publishedAt(String title, String when, int views) {
        Post post = published(title, p -> p.publishedAt = Instant.parse(when));
        update("myapp_post", post.id(), Map.of("view_count", views));
        return post;
    }

    @BeforeEach
    void fixtures() {
        createUser();
        top = publishedAt("Most Read", "2026-03-04T00:00:00Z", 300);
        publishedAt("Middling", "2026-03-20T00:00:00Z", 100);
        publishedAt("Quiet", "2026-05-09T00:00:00Z", 0);
        draft = post("Unfinished");
    }

    Map<String, Object> stats() {
        Response response = owner().get("/api/posts/stats/");
        assertThat(response.status()).isEqualTo(200);
        return response.map();
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> rows(Map<String, Object> stats, String key) {
        return (List<Map<String, Object>>) stats.get(key);
    }

    @Test
    void anonymousCallersAreRefused() {
        assertThat(client.get("/api/posts/stats/").status()).isEqualTo(403);
    }

    @Test
    void totals() {
        Map<String, Object> data = stats();
        assertThat(data.get("total")).isEqualTo(4);
        assertThat(data.get("published")).isEqualTo(3);
        assertThat(data.get("drafts")).isEqualTo(1);
        assertThat(data.get("total_views")).isEqualTo(400);
    }

    @Test
    void averageIsPerPublishedPostNotPerPost() {
        assertThat(stats().get("average_views")).isEqualTo(133.3);
    }

    @Test
    void mostReadIsRankedAndExcludesUnreadPosts() {
        List<Map<String, Object>> rows = rows(stats(), "most_read");
        assertThat(rows.stream().map(r -> r.get("view_count")).toList()).containsExactly(300, 100);
        assertThat(rows.get(0).get("slug")).isEqualTo(top.slug());
        assertThat(rows.get(0).keySet()).containsExactly("slug", "title", "status", "view_count");
        assertThat(rows.stream().map(r -> r.get("slug")).toList()).doesNotContain("quiet");
    }

    @Test
    void publishedByMonthGroupsAndSkipsEmptyMonths() {
        assertThat(stats().get("published_by_month")).isEqualTo(List.of(
                Map.of("month", "2026-03", "count", 2), Map.of("month", "2026-05", "count", 1)));
    }

    @Test
    void draftsAreLeftOutOfTheCadence() {
        int total = rows(stats(), "published_by_month").stream().mapToInt(r -> (Integer) r.get("count")).sum();
        assertThat(total).isEqualTo(3);
    }

    @Test
    void listFiltersDoNotNarrowIt() {
        assertThat(owner().get("/api/posts/stats/", "tag", "books").get("total")).isEqualTo(4);
    }

    @Test
    void anEmptySiteReportsZeroesRatherThanFailing() {
        jdbc.sql("DELETE FROM myapp_post").update();
        Map<String, Object> data = stats();
        assertThat(data.get("total")).isEqualTo(0);
        assertThat(data.get("total_views")).isEqualTo(0);
        assertThat(data.get("average_views")).isEqualTo(0.0);
        assertThat(data.get("views_per_day")).isEqualTo(0.0);
        assertThat(data.get("most_read")).isEqualTo(List.of());
        assertThat(data.get("published_by_month")).isEqualTo(List.of());
        List<Map<String, Object>> days = rows(data, "views_by_day");
        assertThat(days).hasSize(PostStatsService.DAILY_VIEWS_DAYS);
        assertThat(days.stream().map(r -> r.get("count")).collect(Collectors.toSet())).isEqualTo(Set.of(0));
    }

    @Test
    void viewsByDayIsADenseWindowEndingToday() {
        List<Map<String, Object>> days = rows(stats(), "views_by_day");
        assertThat(days).hasSize(PostStatsService.DAILY_VIEWS_DAYS);
        assertThat(days.get(days.size() - 1).get("date")).isEqualTo(today().toString());
        assertThat(days.get(0).get("date")).isEqualTo(today().minusDays(PostStatsService.DAILY_VIEWS_DAYS - 1).toString());
    }

    @Test
    void viewsByDaySumsEveryPostAndDropsOlderDays() {
        viewDay(top, today(), 5);
        viewDay(draft, today(), 2);
        viewDay(top, today().minusDays(3), 4);
        // Outside the window, so it must appear nowhere in the series.
        viewDay(top, today().minusDays(PostStatsService.DAILY_VIEWS_DAYS), 99);

        Map<String, Integer> counts = new HashMap<>();
        for (Map<String, Object> day : rows(stats(), "views_by_day")) {
            counts.put((String) day.get("date"), (Integer) day.get("count"));
        }
        assertThat(counts.get(today().toString())).isEqualTo(7);
        assertThat(counts.get(today().minusDays(3).toString())).isEqualTo(4);
        assertThat(counts.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(11);
    }

    @Test
    void viewsPerDayIsEveryViewOverTheDaysSinceFirstPublication() {
        jdbc.sql("DELETE FROM myapp_post").update();
        Post post = published("Only One", p -> p.publishedAt = Instant.now().minus(9, ChronoUnit.DAYS));
        update("myapp_post", post.id(), Map.of("view_count", 100));
        // Ten days live, both ends counted -- not nine.
        assertThat(stats().get("views_per_day")).isEqualTo(10.0);
    }

    @Test
    void viewsPerDayIgnoresReadsADraftCollectedBeforePublishing() {
        jdbc.sql("DELETE FROM myapp_post").update();
        Post unfinished = post("Unfinished");
        update("myapp_post", unfinished.id(), Map.of("view_count", 12));
        Map<String, Object> data = stats();
        assertThat(data.get("total_views")).isEqualTo(12);
        assertThat(data.get("views_per_day")).isEqualTo(0.0);
    }

    @Test
    void roundingIsHalfToEvenLikePython() {
        assertThat(PostStatsService.round1(0.25)).isEqualTo(0.2);
        assertThat(PostStatsService.round1(133.33333)).isEqualTo(133.3);
        assertThat(PostStatsService.round1(2.675)).isEqualTo(2.7);
    }
}
