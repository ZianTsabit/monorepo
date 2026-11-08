package com.ziantsabit.api.dto;

import java.util.List;

import com.ziantsabit.api.model.Book;
import com.ziantsabit.api.validation.HttpUrl;
import com.ziantsabit.api.validation.Messages;
import com.ziantsabit.api.validation.OneOf;
import com.ziantsabit.api.validation.Required;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The writable fields of a book. The ISBN is checked and the release year's
 * upper bound (next year) applied in BookService, since both depend on more
 * than the value itself.
 */
public record BookRequest(
        @Required
        @Size(max = 200, message = Messages.TOO_LONG) String title,
        @Size(max = 220, message = Messages.TOO_LONG) @Pattern(regexp = "[-a-zA-Z0-9_]*", message = Messages.SLUG)
        String slug,
        // A catalogue entry with no author is not a catalogue entry.
        @Required
        @Size(max = 200, message = Messages.TOO_LONG) String author,
        List<@NotNull(message = Messages.INVALID) @Size(max = 50, message = Messages.TOO_LONG) String> genres,
        @Size(max = 17, message = Messages.TOO_LONG) String isbn,
        @Min(value = Book.EARLIEST_RELEASE_YEAR, message = "Must be {value} or later.") Integer releaseYear,
        String review,
        @HttpUrl @Size(max = 500, message = Messages.TOO_LONG) String coverImageUrl,
        @Size(max = 200, message = Messages.TOO_LONG) String coverImageAlt,
        @OneOf({Book.DRAFT, Book.PUBLISHED}) String status) {

    public static BookRequest of(Book book) {
        return new BookRequest(book.title(), book.slug(), book.author(), book.genres(), book.isbn(),
                book.releaseYear(), book.review(), book.coverImageUrl(), book.coverImageAlt(), book.status());
    }
}
