package com.ziantsabit.api.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.ziantsabit.api.dto.LabelCount;
import com.ziantsabit.api.model.Book;
import com.ziantsabit.api.model.BookFields;
import com.ziantsabit.api.query.BookQuery;
import com.ziantsabit.api.util.Sql;

/** SQL for {@code myapp_book}. */
@Repository
public class BookRepository {

    private final JdbcClient jdbc;

    public BookRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long count(BookQuery query) {
        Sql.Where where = query.where();
        return jdbc.sql("SELECT count(*) FROM myapp_book b" + where.sql())
                .params(where.params()).query(Long.class).single();
    }

    public List<Book> find(BookQuery query, int limit, long offset) {
        Sql.Where where = query.where();
        return jdbc.sql("SELECT b.* FROM myapp_book b" + where.sql() + query.orderBy()
                        + " LIMIT :limit OFFSET :offset")
                .params(where.params()).param("limit", limit).param("offset", offset)
                .query(BookRepository::book).list();
    }

    public Optional<Book> findBySlug(BookQuery query, String slug) {
        Sql.Where where = query.where().and("b.slug = :slug", "slug", slug);
        return jdbc.sql("SELECT b.* FROM myapp_book b" + where.sql()).params(where.params())
                .query(BookRepository::book).optional();
    }

    public Book get(long id) {
        return jdbc.sql("SELECT * FROM myapp_book WHERE id = :id").param("id", id)
                .query(BookRepository::book).single();
    }

    public boolean slugTaken(String slug, Long excludeId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM myapp_book WHERE slug = :slug AND id IS DISTINCT FROM :id)")
                .param("slug", slug).param("id", excludeId).query(Boolean.class).single();
    }

    /** The first other book with this title and author, ignoring case. */
    public Optional<Book> sameBook(String title, String author, Long excludeId) {
        return jdbc.sql("""
                SELECT * FROM myapp_book
                 WHERE upper(title) = upper(:title) AND upper(author) = upper(:author)
                   AND id IS DISTINCT FROM :id
                 ORDER BY created_at DESC, id DESC
                 LIMIT 1""")
                .param("title", title).param("author", author).param("id", excludeId)
                .query(BookRepository::book).optional();
    }

    public long insert(BookFields book, Instant now) {
        return jdbc.sql("""
                INSERT INTO myapp_book (title, slug, author, genres, isbn, release_year, review,
                                        cover_image_url, cover_image_alt, status, created_at, updated_at)
                VALUES (:title, :slug, :author, :genres, :isbn, :releaseYear, :review,
                        :coverImageUrl, :coverImageAlt, :status, :now, :now)
                RETURNING id""")
                .params(columns(book)).param("now", Sql.timestamp(now))
                .query(Long.class).single();
    }

    public void update(long id, BookFields book, Instant now) {
        jdbc.sql("""
                UPDATE myapp_book
                   SET title = :title, slug = :slug, author = :author, genres = :genres, isbn = :isbn,
                       release_year = :releaseYear, review = :review, cover_image_url = :coverImageUrl,
                       cover_image_alt = :coverImageAlt, status = :status, updated_at = :now
                 WHERE id = :id""")
                .params(columns(book)).param("now", Sql.timestamp(now)).param("id", id)
                .update();
    }

    public void delete(long id) {
        jdbc.sql("DELETE FROM myapp_book WHERE id = :id").param("id", id).update();
    }

    public List<LabelCount> genreSpellings(BookQuery query) {
        Sql.Where where = query.where();
        return jdbc.sql("SELECT g.label AS name, count(*) AS count FROM myapp_book b "
                        + "CROSS JOIN LATERAL unnest(b.genres) AS g(label)" + where.sql() + " GROUP BY g.label")
                .params(where.params())
                .query((row, n) -> new LabelCount(row.getString("name"), row.getLong("count"))).list();
    }

    private static Map<String, Object> columns(BookFields book) {
        Map<String, Object> columns = new HashMap<>();
        columns.put("title", book.title);
        columns.put("slug", book.slug);
        columns.put("author", book.author);
        columns.put("genres", Sql.textArray(book.genres));
        columns.put("isbn", book.isbn);
        columns.put("releaseYear", book.releaseYear);
        columns.put("review", book.review);
        columns.put("coverImageUrl", book.coverImageUrl);
        columns.put("coverImageAlt", book.coverImageAlt);
        columns.put("status", book.status);
        return columns;
    }

    private static Book book(ResultSet row, int rowNum) throws SQLException {
        return new Book(
                row.getLong("id"),
                row.getString("title"),
                row.getString("slug"),
                row.getString("author"),
                Sql.strings(row, "genres"),
                row.getString("isbn"),
                row.getObject("release_year", Integer.class),
                row.getString("review"),
                row.getString("cover_image_url"),
                row.getString("cover_image_alt"),
                row.getString("status"),
                Sql.instant(row, "created_at"),
                Sql.instant(row, "updated_at"));
    }
}
