package com.ziantsabit.api.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ziantsabit.api.dto.ReactionCount;
import com.ziantsabit.api.dto.ReactionSummary;
import com.ziantsabit.api.exception.ApiException;
import com.ziantsabit.api.model.Post;
import com.ziantsabit.api.model.Reaction;
import com.ziantsabit.api.repository.ReactionRepository;
import com.ziantsabit.api.security.Viewer;
import com.ziantsabit.api.validation.Form;

/** The emoji bar: reading it, and a visitor toggling one emoji. */
@Service
public class ReactionService {

    private final ReactionRepository reactions;

    public ReactionService(ReactionRepository reactions) {
        this.reactions = reactions;
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
    public ReactionSummary react(Post post, Object body) {
        Form form = Form.of(body, false);
        String emoji = form.choice("emoji", true, Reaction.EMOJI_VALUES, false).orElse(null);
        // Non-blank, or everyone who sent nothing would be one visitor sharing
        // one reaction, and the toggle would take away a stranger's.
        String visitor = form.text("visitor", true, false, 64).orElse(null);
        form.check();

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
