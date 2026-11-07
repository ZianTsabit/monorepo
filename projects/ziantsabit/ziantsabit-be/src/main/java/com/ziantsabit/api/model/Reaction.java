package com.ziantsabit.api.model;

import java.util.List;

/**
 * The emoji bar under a post. A row per (post, emoji, visitor) in
 * {@code myapp_reaction}; this class holds the fixed vocabulary.
 *
 * <p>{@code visitor} is not a user. It is an opaque token the browser makes up
 * once and keeps in localStorage, so uniqueness is a convenience, not a
 * guarantee -- clearing site data buys another reaction, as it always did for
 * the view counter too.
 */
public final class Reaction {

    /**
     * What a visitor may react with, in the order the bar shows them, each with
     * the name a screen reader reads out.
     *
     * <p>A fixed set rather than free text: a one-tap gesture needs a small
     * vocabulary identical on every post, or the counts fragment across spellings.
     * The API sends it with the counts, so the SPA has no copy to drift. Adding
     * one is a code change and no migration; removing one leaves its rows in
     * place and simply stops offering them.
     */
    public static final List<Emoji> EMOJI = List.of(
            new Emoji(Character.toString(0x1F44D), "Like"),
            new Emoji(Character.toString(0x1F389), "Celebrate"),
            new Emoji(Character.toString(0x1F525), "Fire"),
            // The variation selector is load-bearing: U+2764 alone is a text
            // character and renders as a small monochrome heart in the page font.
            new Emoji("❤️", "Love"),
            new Emoji(Character.toString(0x1F602), "Funny"),
            new Emoji(Character.toString(0x1F632), "Surprising"),
            new Emoji(Character.toString(0x1F914), "Thinking"));

    public static final List<String> EMOJI_VALUES = EMOJI.stream().map(Emoji::emoji).toList();

    public record Emoji(String emoji, String label) {
    }

    private Reaction() {
    }
}
