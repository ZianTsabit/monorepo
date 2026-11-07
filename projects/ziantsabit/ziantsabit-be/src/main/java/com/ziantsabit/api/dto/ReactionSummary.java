package com.ziantsabit.api.dto;

import java.util.List;

/** The body of {@code GET|POST /api/posts/{slug}/reactions/}. */
public record ReactionSummary(String slug, long total, List<ReactionCount> reactions) {
}
