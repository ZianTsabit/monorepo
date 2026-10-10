package com.ziantsabit.api.model;

import java.time.Instant;
import java.util.List;

/**
 * One book in the owner's catalogue -- a thing that exists in the world, with
 * an author who is not the site's owner, a release year and an ISBN, which is
 * why it is its own table rather than a kind of post.
 */
public record Book(
        long id,
        String title,
        String slug,
        // Free text rather than an Author table: "Le Guin, Ursula K." vs
        // "Ursula K. Le Guin" would become two rows in a table that promised to
        // prevent exactly that. Several authors go in as one string.
        String author,
        List<String> genres,
        // Stored without separators (see Isbn), so a search matches however the
        // number was written on the copy in hand. Deliberately not unique.
        String isbn,
        Integer releaseYear,
        // The owner's writing about the book, as Markdown.
        String review,
        String coverImageUrl,
        String coverImageAlt,
        String status,
        Instant createdAt,
        Instant updatedAt) {

    public static final String DRAFT = "draft";
    public static final String PUBLISHED = "published";
    public static final List<String> STATUSES = List.of(DRAFT, PUBLISHED);

    /** Gutenberg's press: a bare typo ("19" for "1985") is likelier than a medieval entry. */
    public static final int EARLIEST_RELEASE_YEAR = 1450;
}
