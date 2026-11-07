package com.ziantsabit.api.model;

import java.time.Instant;
import java.util.List;

/**
 * One visitor's comment on one post.
 *
 * <p><b>Published on arrival and moderated afterwards.</b> A queue on a
 * personal site means a comment sits invisible for days and its writer posts
 * it again. {@code status} is what takes one down, as a hide rather than a
 * delete, so the row is still there to look at.
 *
 * <p><b>There is deliberately no email field.</b> Nothing here sends mail, so
 * an address would exist only to be leaked.
 *
 * <p>{@code post} is the post's slug and {@code postTitle} its title, for the
 * admin console's rows, which list comments across posts.
 */
public record Comment(
        long id,
        String post,
        String postTitle,
        // Display text, not identity: there are no visitor accounts.
        String authorName,
        // Plain text, never Markdown -- a stranger's markup rendered is an
        // injection surface. The SPA shows it with white-space: pre-line.
        String body,
        String status,
        Instant createdAt,
        Instant updatedAt) {

    public static final String PUBLISHED = "published";
    public static final String HIDDEN = "hidden";
    public static final List<String> STATUSES = List.of(PUBLISHED, HIDDEN);
}
