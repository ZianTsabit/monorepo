package com.ziantsabit.api.dto;

import java.util.List;

/**
 * The body of {@code GET /api/posts/stats/}: aggregates for the admin's
 * statistics page, computed server-side because the list is paginated and a
 * client adding up view counts itself would be wrong past the first page.
 */
public record PostStats(
        long total,
        long published,
        long drafts,
        long totalViews,
        // Per *published* post: a draft has no public page to be read on, so
        // counting drafts would drag the average down for no reason.
        double averageViews,
        // Every view ever counted over the days since the first publication --
        // a lifetime rate that covers reads from before per-day history existed.
        double viewsPerDay,
        List<MostRead> mostRead,
        // Only months that have a post; filling the gaps is the chart's call.
        List<MonthCount> publishedByMonth,
        // Exactly PostStatsService.DAILY_VIEWS_DAYS rows ending today, *including* empty days:
        // the window is anchored to the server's clock, which a client cannot
        // know.
        List<DayCount> viewsByDay) {

    public record MostRead(String slug, String title, String status, long viewCount) {
    }

    public record MonthCount(String month, long count) {
    }

    public record DayCount(String date, long count) {
    }
}
