package com.ziantsabit.api.query;

import java.util.List;
import java.util.Map;

import com.ziantsabit.api.model.Book;
import com.ziantsabit.api.security.Viewer;
import com.ziantsabit.api.util.Sql;
import com.ziantsabit.api.validation.Isbn;
import com.ziantsabit.api.validation.QueryParams;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Which books a request may see, and in what order. Query params:
 * {@code ?genre=} (containment, case-insensitive), {@code ?search=} (title,
 * author or ISBN), {@code ?status=} (the owner only), and
 * {@code ?ordering=recent|title|author|year}.
 */
public record BookQuery(boolean publishedOnly, String status, String genre, String search, String ordering) {

    // Every ordering ends in a total tie-break, so a page boundary cannot fall
    // between two rows the database may return either way round -- which, on a
    // catalogue sorted by author, would show one book twice and drop another.
    public static final Map<String, String> ORDERINGS = Map.of(
            "recent", "b.created_at DESC, b.id DESC",
            "title", "b.title ASC, b.id DESC",
            "author", "b.author ASC, b.title ASC, b.id DESC",
            // Undated books last: a bare DESC puts NULLs first in Postgres,
            // which reads as "newest" when it means "unknown".
            "year", "b.release_year DESC NULLS LAST, b.title ASC, b.id DESC");
    public static final List<String> ORDERING_NAMES = ORDERINGS.keySet().stream().sorted().toList();

    public static BookQuery of(HttpServletRequest request) {
        String genre = QueryParams.trimmed(request.getParameter("genre"));
        String search = QueryParams.trimmed(request.getParameter("search"));
        String ordering = request.getParameter("ordering");
        if (ordering == null || ordering.isEmpty()) {
            ordering = "recent";
        } else {
            QueryParams.rejectUnknown(ordering, ORDERING_NAMES, "ordering");
        }
        // Unpublished entries are hidden on the detail route too, not just the
        // list, or a draft is one guessed slug away.
        if (!Viewer.isAuthenticated()) {
            return new BookQuery(true, null, genre, search, ordering);
        }
        String status = request.getParameter("status");
        if (status == null || status.isEmpty()) {
            status = null;
        } else {
            QueryParams.rejectUnknown(status, Book.STATUSES, "status");
        }
        return new BookQuery(false, status, genre, search, ordering);
    }

    public Sql.Where where() {
        Sql.Where where = new Sql.Where();
        if (publishedOnly) {
            where.and("b.status = :visibleStatus", "visibleStatus", Book.PUBLISHED);
        } else if (status != null) {
            where.and("b.status = :status", "status", status);
        }
        if (!genre.isEmpty()) {
            where.and("EXISTS (SELECT 1 FROM unnest(b.genres) AS g(label) WHERE lower(g.label) = lower(:genre))",
                    "genre", genre);
        }
        if (!search.isEmpty()) {
            // ISBNs are stored without separators, so the term is stripped of
            // them too -- a number pasted off a back cover has to match.
            String isbn = Isbn.normalise(search);
            String clause = "(b.title ILIKE :search OR b.author ILIKE :search"
                    + (isbn.isEmpty() ? "" : " OR b.isbn ILIKE :isbn") + ")";
            where.and(clause, "search", Sql.containing(search));
            if (!isbn.isEmpty()) {
                where.param("isbn", Sql.containing(isbn));
            }
        }
        return where;
    }

    public String orderBy() {
        return " ORDER BY " + ORDERINGS.get(ordering);
    }
}
