package com.ziantsabit.api.dto;

import com.ziantsabit.api.model.Comment;
import com.ziantsabit.api.validation.Messages;
import com.ziantsabit.api.validation.OneOf;
import com.ziantsabit.api.validation.Required;

import jakarta.validation.constraints.Size;

/**
 * A comment: a visitor's new one, or the owner's edit of one. {@code post} is
 * the post's slug. {@code status} is honoured only for the owner; a visitor's
 * comment is always published.
 */
public record CommentRequest(
        @Required String post,
        @Required(blank = "Give a name to post under.")
        @Size(max = 80, message = Messages.TOO_LONG) String authorName,
        @Required(blank = "Write something first.")
        @Size(max = 2000, message = Messages.TOO_LONG) String body,
        @OneOf({Comment.PUBLISHED, Comment.HIDDEN}) String status) {

    public static CommentRequest of(Comment comment) {
        return new CommentRequest(comment.post(), comment.authorName(), comment.body(), comment.status());
    }
}
