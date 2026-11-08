package com.ziantsabit.api.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ziantsabit.api.dto.ReactionCount;
import com.ziantsabit.api.dto.ReactionRequest;
import com.ziantsabit.api.dto.ReactionSummary;
import com.ziantsabit.api.exception.ApiException;
import com.ziantsabit.api.model.Post;
import com.ziantsabit.api.model.Reaction;
import com.ziantsabit.api.repository.ReactionRepository;
import com.ziantsabit.api.security.Viewer;
import com.ziantsabit.api.validation.RequestBodies;

import tools.jackson.databind.JsonNode;

/** The emoji bar: reading it, and a visitor toggling one emoji. */
@Service
public class ReactionService {

    private final ReactionRepository reactions;
    private final RequestBodies bodies;

    public ReactionService(ReactionRepository reactions, RequestBodies bodies) {
        this.reactions = reactions;
        this.bodies = bodies;
    }

    /**
     * The whole bar for one post, dense over {@link Reaction#EMOJI} -- zeros
     * included, so the client renders the row the server defines.
     *
     * <p>{@code total} sums only the emoji on offer: a reaction left with one
     * since retired stays in the table but is no longer shown, and a total that
     * counted it would disagree with the buttons under it.
     */
    public ReactionSummary summarise(Post post, String visitor) {
        Map<String, Long> counts = reactions.countsByEmoji(post.id());
        Set<String> mine = visitor.isEmpty() ? Set.of() : reactions.emojiOf(post.id(), visitor);
        List<ReactionCount> rows = new ArrayList<>();
        long total = 0;
        for (Reaction.Emoji emoji : Reaction.EMOJI) {
            long count = counts.getOrDefault(emoji.emoji(), 0L);
            total += count;
            rows.add(new ReactionCount(emoji.emoji(), emoji.label(), count, mine.contains(emoji.emoji())));
        }
        return new ReactionSummary(post.slug(), total, rows);
    }

    /**
     * Toggle one emoji for one browser and return the bar again: added if it
     * was not there, taken away if it was.
     */
    @Transactional
    public ReactionSummary react(Post post, JsonNode body) {
        ReactionRequest request = bodies.read(body, ReactionRequest.class);
        String emoji = request.emoji();
        String visitor = request.visitor().strip();
        if (!Reaction.EMOJI_VALUES.contains(emoji)) {
            throw ApiException.field("emoji", "Must be one of: " + String.join(", ", Reaction.EMOJI_VALUES) + ".");
        }

        // The owner is exempt, as from a closed comment thread: the switch is
        // about what visitors may add.
        if (!post.reactionsEnabled() && !Viewer.isAuthenticated()) {
            throw ApiException.field("emoji", "Reactions are turned off for this post.");
        }
        // Delete first, so un-reacting is one statement and the database
        // decides rather than a read this request would then have to trust.
        if (!reactions.delete(post.id(), emoji, visitor)) {
            reactions.insert(post.id(), emoji, visitor);
        }
        return summarise(post, visitor);
    }
}
