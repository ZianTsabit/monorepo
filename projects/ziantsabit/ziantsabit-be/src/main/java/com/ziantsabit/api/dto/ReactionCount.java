package com.ziantsabit.api.dto;

/** One button of the reaction bar; {@code reacted} is whether the asking browser picked it. */
public record ReactionCount(String emoji, String label, long count, boolean reacted) {
}
