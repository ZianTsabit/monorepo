package com.ziantsabit.api.dto;

/** One row of a vocabulary listing: {@code /api/posts/tags/} or {@code /api/books/genres/}. */
public record LabelCount(String name, long count) {
}
