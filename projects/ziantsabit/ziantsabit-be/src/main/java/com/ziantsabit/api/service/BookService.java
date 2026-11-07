package com.ziantsabit.api.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ziantsabit.api.dto.LabelCount;
import com.ziantsabit.api.exception.ApiException;
import com.ziantsabit.api.model.Book;
import com.ziantsabit.api.model.BookFields;
import com.ziantsabit.api.query.BookQuery;
import com.ziantsabit.api.repository.BookRepository;
import com.ziantsabit.api.util.Labels;
import com.ziantsabit.api.util.Slugs;
import com.ziantsabit.api.util.Sql;
import com.ziantsabit.api.validation.Form;
import com.ziantsabit.api.validation.Isbn;

/** Validating and saving catalogue entries. */
@Service
public class BookService {

    public static final int LABEL_LIMIT = 100;
    public static final String SLUG_TAKEN = "book with this slug already exists.";

    private final BookRepository books;
    private final Clock clock;

    public BookService(BookRepository books, Clock clock) {
        this.books = books;
        this.clock = clock;
    }

    public long count(BookQuery query) {
        return books.count(query);
    }

    public List<Book> find(BookQuery query, int limit, long offset) {
        return books.find(query, limit, offset);
    }

    /** Every genre in the catalogue with how many books carry it, scoped like the list. */
    public List<LabelCount> genres(BookQuery query) {
        return Labels.counts(books.genreSpellings(query), LABEL_LIMIT);
    }

    public Book visible(BookQuery query, String slug) {
        return books.findBySlug(query, slug)
                .orElseThrow(() -> ApiException.notFound("No Book matches the given query."));
    }

    @Transactional
    public void delete(Book book) {
        books.delete(book.id());
    }

    @Transactional
    public Book create(Object body) {
        BookFields fields = new BookFields();
        read(Form.of(body, false), fields, null);
        return save(null, fields);
    }

    @Transactional
    public Book update(Book book, Object body, boolean partial) {
        BookFields fields = BookFields.of(book);
        read(Form.of(body, partial), fields, book.id());
        return save(book.id(), fields);
    }

    /**
     * Store a book, tidying genres and the ISBN and deriving a slug when none
     * was given: the title, then the title and author when the title alone is
     * taken -- two books called "Ulysses" is ordinary, and
     * {@code /ulysses-james-joyce} says which one it is where
     * {@code /ulysses-2} does not.
     */
    @Transactional
    public Book save(Long id, BookFields fields) {
        Instant now = Sql.now();
        fields.genres = Labels.clean(fields.genres);
        fields.isbn = Isbn.normalise(fields.isbn);
        if (fields.slug == null || fields.slug.isEmpty()) {
            fields.slug = Slugs.unique(slugBase(fields, id), 220, slug -> books.slugTaken(slug, id));
        }
        try {
            if (id == null) {
                return books.get(books.insert(fields, now));
            }
            books.update(id, fields, now);
            return books.get(id);
        } catch (DuplicateKeyException race) {
            throw ApiException.field("slug", SLUG_TAKEN);
        }
    }

    /** One ahead of this year: a book bought in December can carry the next one. */
    public int latestReleaseYear() {
        return LocalDate.now(clock.withZone(ZoneOffset.UTC)).getYear() + 1;
    }

    private String slugBase(BookFields fields, Long id) {
        String base = Slugs.slugify(fields.title);
        if (base.isEmpty()) {
            base = "book";
        }
        if (!books.slugTaken(base, id)) {
            return base;
        }
        String author = Slugs.slugify(fields.author);
        return author.isEmpty() ? base : base + "-" + author;
    }

    private void read(Form form, BookFields fields, Long id) {
        form.text("title", true, false, 200).ifPresent(title -> fields.title = title);
        form.slug("slug", 220).ifPresent(slug -> {
            if (slug.isEmpty()) {
                form.error("slug", "Leave slug out entirely to have it generated from the title.");
            } else if (books.slugTaken(slug, id)) {
                form.error("slug", SLUG_TAKEN);
            } else {
                fields.slug = slug;
            }
        });
        // A catalogue entry with no author is not a catalogue entry.
        form.text("author", true, false, 200).ifPresent(author -> fields.author = author);
        form.labels("genres", 50).ifPresent(genres -> fields.genres = Labels.clean(genres));
        form.text("isbn", false, true, 17).ifPresent(raw -> {
            String compact = Isbn.normalise(raw);
            if (!compact.isEmpty() && !Isbn.isValid(compact)) {
                form.error("isbn", "That is not a valid ISBN. Give 10 or 13 digits, hyphens optional.");
            } else {
                fields.isbn = compact;
            }
        });
        form.nullableInteger("release_year", Book.EARLIEST_RELEASE_YEAR).ifPresent(year -> {
            // The ceiling moves with the calendar, so the message names it.
            int latest = latestReleaseYear();
            if (year.isPresent() && year.get() > latest) {
                form.error("release_year",
                        "Give a year between " + Book.EARLIEST_RELEASE_YEAR + " and " + latest + ".");
            } else {
                fields.releaseYear = year.orElse(null);
            }
        });
        form.text("review", false, true, 0).ifPresent(review -> fields.review = review);
        form.url("cover_image_url", 500).ifPresent(url -> fields.coverImageUrl = url);
        form.text("cover_image_alt", false, true, 200).ifPresent(alt -> fields.coverImageAlt = alt);
        form.choice("status", false, Book.STATUSES, false).ifPresent(status -> fields.status = status);
        form.check();
        refuseDuplicate(fields, id);
    }

    /**
     * Refuse a second entry for a book already on the shelf.
     *
     * <p>Title <em>and</em> author, ignoring case, because neither alone
     * identifies a book. An update excludes itself, or every autosave PATCH
     * would collide with the row it is rewriting; a PATCH sending only one half
     * is checked against the stored other half. The message names the entry it
     * collided with -- usually a draft the owner forgot they had started.
     */
    private void refuseDuplicate(BookFields fields, Long id) {
        if (fields.title.isEmpty() || fields.author.isEmpty()) {
            return;
        }
        Optional<Book> existing = books.sameBook(fields.title, fields.author, id);
        existing.ifPresent(book -> {
            throw ApiException.nonField("\"" + book.title() + "\" by " + book.author()
                    + " is already in the catalogue (/books/" + book.slug()
                    + "). Edit that entry rather than adding a second one.");
        });
    }
}
