package com.ziantsabit.api.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.ziantsabit.api.dto.PostStats;
import com.ziantsabit.api.repository.PostRepository;

/** {@code GET /api/posts/stats/}: the admin statistics page's aggregates. */
@Service
public class PostStatsService {

    /** How many rows the "most read" table asks for: a ranking rather than a podium. */
    public static final int MOST_READ_LIMIT = 10;
    /** The daily-reads chart's window, today included. */
    public static final int DAILY_VIEWS_DAYS = 30;

    private final PostRepository posts;
    private final Clock clock;

    public PostStatsService(PostRepository posts, Clock clock) {
        this.posts = posts;
        this.clock = clock;
    }

    /**
     * Built from every post regardless of the list's filters: this is an
     * overview of everything, and a {@code ?tag=} leaking in would quietly
     * answer a different question.
     */
    public PostStats compute() {
        PostRepository.Totals totals = posts.totals();
        // One reading of the clock, so the window and the rate cannot land on
        // different days when this runs across midnight.
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));

        double viewsPerDay = posts.firstPublishedAt()
                .map(first -> {
                    // Both ends inclusive, so the first day is a day; max() guards
                    // a publish date set in the future.
                    long days = Math.max(1, ChronoUnit.DAYS.between(first.atZone(ZoneOffset.UTC).toLocalDate(),
                            today) + 1);
                    return round1((double) totals.totalViews() / days);
                })
                .orElse(0.0);

        LocalDate windowStart = today.minusDays(DAILY_VIEWS_DAYS - 1);
        Map<LocalDate, Long> recorded = posts.viewsByDay(windowStart, today);
        List<PostStats.DayCount> viewsByDay = new ArrayList<>(DAILY_VIEWS_DAYS);
        for (int offset = 0; offset < DAILY_VIEWS_DAYS; offset++) {
            LocalDate day = windowStart.plusDays(offset);
            viewsByDay.add(new PostStats.DayCount(day.toString(), recorded.getOrDefault(day, 0L)));
        }

        return new PostStats(
                totals.total(),
                totals.published(),
                totals.total() - totals.published(),
                totals.totalViews(),
                round1(totals.averageViews()),
                viewsPerDay,
                posts.mostRead(MOST_READ_LIMIT),
                posts.publishedByMonth(),
                viewsByDay);
    }

    /** Python's round(x, 1): half-to-even on the double's exact value. */
    public static double round1(double value) {
        return new BigDecimal(value).setScale(1, RoundingMode.HALF_EVEN).doubleValue();
    }
}
