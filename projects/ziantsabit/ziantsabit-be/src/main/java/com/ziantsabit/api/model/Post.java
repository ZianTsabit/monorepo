package com.ziantsabit.api.model;

import java.time.Instant;
import java.util.List;

/**
 * One post, as stored and as served. The component order is the JSON field
 * order the API has always used.
 *
 * <p>{@code commentCount} is the number of <em>visible</em> comments, even for
 * the owner: it is the figure a visitor sees under the post, and an admin list
 * reporting a different number under the same word would be wrong. The admin
 * console counts hidden ones by filtering {@code /api/comments/} instead.
 */
public record Post(
        long id,
        String title,
        String slug,
        String excerpt,
        String body,
        String coverImageUrl,
        String coverImageAlt,
        List<String> tags,
        String status,
        Instant publishedAt,
        boolean commentsEnabled,
        boolean reactionsEnabled,
        String theme,
        long viewCount,
        long commentCount,
        Instant createdAt,
        Instant updatedAt) {

    public static final String DRAFT = "draft";
    public static final String PUBLISHED = "published";
    public static final List<String> STATUSES = List.of(DRAFT, PUBLISHED);

    /**
     * The site's colour schemes, by the names the SPA's {@code theme.ts} knows
     * them by. Blank, the default, means the reader's own choice stands. A
     * fourth scheme is a change here and in the SPA together.
     */
    public static final List<String> THEMES = List.of("light", "dark", "rain");
}
