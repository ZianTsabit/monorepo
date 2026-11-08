package com.ziantsabit.api.dto;

import java.time.Instant;
import java.util.List;

import com.ziantsabit.api.model.Post;
import com.ziantsabit.api.validation.HttpUrl;
import com.ziantsabit.api.validation.Messages;
import com.ziantsabit.api.validation.OneOf;
import com.ziantsabit.api.validation.Required;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The writable fields of a post, as a create sends them or a PATCH merges into
 * them. A null optional field on a create takes the post's default.
 *
 * <p>{@code slug}: absent means "derive one from the title"; blank is refused
 * (see PostService), because on an update it would silently change a published
 * URL.
 */
public record PostRequest(
        @Required
        @Size(max = 200, message = Messages.TOO_LONG) String title,
        @Size(max = 220, message = Messages.TOO_LONG) @Pattern(regexp = "[-a-zA-Z0-9_]*", message = Messages.SLUG)
        String slug,
        String excerpt,
        String body,
        @HttpUrl @Size(max = 500, message = Messages.TOO_LONG) String coverImageUrl,
        @Size(max = 200, message = Messages.TOO_LONG) String coverImageAlt,
        List<@NotNull(message = Messages.INVALID) @Size(max = 50, message = Messages.TOO_LONG) String> tags,
        @OneOf({Post.DRAFT, Post.PUBLISHED}) String status,
        Instant publishedAt,
        Boolean commentsEnabled,
        Boolean reactionsEnabled,
        @OneOf(value = {"light", "dark", "rain"}, allowBlank = true) String theme) {

    public static PostRequest of(Post post) {
        return new PostRequest(post.title(), post.slug(), post.excerpt(), post.body(), post.coverImageUrl(),
                post.coverImageAlt(), post.tags(), post.status(), post.publishedAt(), post.commentsEnabled(),
                post.reactionsEnabled(), post.theme());
    }
}
