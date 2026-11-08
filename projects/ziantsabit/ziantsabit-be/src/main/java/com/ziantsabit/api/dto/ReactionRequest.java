package com.ziantsabit.api.dto;

import com.ziantsabit.api.validation.Messages;
import com.ziantsabit.api.validation.Required;

import jakarta.validation.constraints.Size;

/**
 * One toggle of the reaction bar. {@code visitor} is the browser's own random
 * id: non-blank, or everyone who sent nothing would share one reaction and the
 * toggle would take away a stranger's. The emoji is checked against
 * {@code Reaction.EMOJI} by ReactionService.
 */
public record ReactionRequest(
        @Required String emoji,
        @Required
        @Size(max = 64, message = Messages.TOO_LONG) String visitor) {
}
