package com.ziantsabit.api.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The writable half of a post, as a form fills it in. Starts from the column
 * defaults for a create, or from the stored post for an update, and only the
 * fields a request actually sends are changed.
 */
public final class PostFields {

    public String title = "";
    /** Blank means "derive one from the title" when the post is saved. */
    public String slug = "";
    public String excerpt = "";
    public String body = "";
    public String coverImageUrl = "";
    public String coverImageAlt = "";
    public List<String> tags = new ArrayList<>();
    public String status = Post.DRAFT;
    public Instant publishedAt;
    // Both default on, so a new post behaves like every one before the
    // switches existed. Turning either off is about what visitors may *add*.
    public boolean commentsEnabled = true;
    public boolean reactionsEnabled = true;
    public String theme = "";

    public static PostFields of(Post post) {
        PostFields fields = new PostFields();
        fields.title = post.title();
        fields.slug = post.slug();
        fields.excerpt = post.excerpt();
        fields.body = post.body();
        fields.coverImageUrl = post.coverImageUrl();
        fields.coverImageAlt = post.coverImageAlt();
        fields.tags = new ArrayList<>(post.tags());
        fields.status = post.status();
        fields.publishedAt = post.publishedAt();
        fields.commentsEnabled = post.commentsEnabled();
        fields.reactionsEnabled = post.reactionsEnabled();
        fields.theme = post.theme();
        return fields;
    }
}
