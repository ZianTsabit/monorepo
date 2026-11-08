package com.ziantsabit.api.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ziantsabit.api.dto.BookRequest;
import com.ziantsabit.api.dto.LabelCount;
import com.ziantsabit.api.exception.ApiException;
import com.ziantsabit.api.model.Book;
import com.ziantsabit.api.model.BookFields;
import com.ziantsabit.api.query.BookQuery;
import com.ziantsabit.api.repository.BookRepository;
import com.ziantsabit.api.util.Labels;
import com.ziantsabit.api.util.Slugs;
import com.ziantsabit.api.util.Sql;
import com.ziantsabit.api.validation.Isbn;
import com.ziantsabit.api.validation.RequestBodies;

import tools.jackson.databind.JsonNode;

/** Validating and saving catalogue entries. */
@Service
public class BookService {

    public static final int LABEL_LIMIT = 100;
    public static final String SLUG_TAKEN = "A book with this slug already exists.";

    private final BookRepository books;
    private final RequestBodies bodies;
    private final Clock clock;

    public BookService(BookRepository books, RequestBodies bodies, Clock clock) {
        this.books = books;
        this.bodies = bodies;
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
    public Book create(JsonNode body) {
        BookRequest request = bodies.read(body, BookRequest.class);
        return save(null, fields(request, null));
    }

    /** PATCH: the body is merged onto the stored book, so a field left out keeps its value. */
    @Transactional
    public Book update(Book book, JsonNode body) {
        BookRequest request = bodies.patch(BookRequest.of(book), body, BookRequest.class);
        return save(book.id(), fields(request, book.id()));
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

    /**
     * A validated request as fields to store, after the checks that need more
     * than the value itself: slug uniqueness, the ISBN checksum, a release year
     * no later than next year, and the same book twice.
     */
    private BookFields fields(BookRequest request, Long id) {
        Map<String, Object> errors = new LinkedHashMap<>();
        if (request.slug() != null) {
            if (request.slug().isEmpty()) {
                errors.put("slug", List.of("Leave slug out entirely to have it generated from the title."));
            } else if (books.slugTaken(request.slug(), id)) {
                errors.put("slug", List.of(SLUG_TAKEN));
            }
        }
        String isbn = Isbn.normalise(request.isbn() == null ? "" : request.isbn());
        if (!isbn.isEmpty() && !Isbn.isValid(isbn)) {
            errors.put("isbn", List.of("That is not a valid ISBN. Give 10 or 13 digits, hyphens optional."));
        }
        // The ceiling moves with the calendar, so the message names it.
        int latest = latestReleaseYear();
        if (request.releaseYear() != null && request.releaseYear() > latest) {
            errors.put("release_year",
                    List.of("Give a year between " + Book.EARLIEST_RELEASE_YEAR + " and " + latest + "."));
        }
        if (!errors.isEmpty()) {
            throw ApiException.invalid(errors);
        }

        BookFields fields = new BookFields();
        fields.title = request.title().strip();
        fields.slug = request.slug() == null ? "" : request.slug();
        fields.author = request.author().strip();
        fields.genres = Labels.clean(request.genres() == null ? List.of() : request.genres());
        fields.isbn = isbn;
        fields.releaseYear = request.releaseYear();
        fields.review = orBlank(request.review());
        fields.coverImageUrl = orBlank(request.coverImageUrl());
        fields.coverImageAlt = orBlank(request.coverImageAlt());
        if (request.status() != null) {
            fields.status = request.status();
        }
        refuseDuplicate(fields, id);
        return fields;
    }

    private static String orBlank(String value) {
        return value == null ? "" : value.strip();
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
