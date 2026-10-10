package com.ziantsabit.api.dto;

/** The body of {@code POST /api/posts/{slug}/view/}. */
public record ViewCount(String slug, long viewCount) {
}
