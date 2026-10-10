package com.ziantsabit.api.query;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.ziantsabit.api.model.Post;
import com.ziantsabit.api.security.Viewer;
import com.ziantsabit.api.util.Sql;
import com.ziantsabit.api.validation.QueryParams;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Which posts a request may see, and in what order -- the one place both are
 * decided, for the list and for every route addressed by slug.
 *
 * <p>Query params:
 * <ul>
 *   <li>{@code ?tag=} containment, case-insensitive; an unused tag is an empty
 *       result, never an error, since tags are free text
 *   <li>{@code ?status=draft|published} the owner only; a visitor always sees
 *       published posts and nothing else, whatever they ask for
 *   <li>{@code ?ordering=recent|updated|views} default recent
 *   <li>{@code ?published_after=} / {@code ?published_before=} YYYY-MM-DD, both
 *       inclusive, compared against the date the post displays
 * </ul>
 *
 * <p><b>Drafts are hidden from visitors on every route, not just the list.</b>
 * Filtering only the list would still hand an unpublished post to anyone who
 * guessed its slug.
 */
public record PostQuery(boolean publishedOnly, String status, String tag, LocalDate after, LocalDate before,
        String ordering) {

    /** What {@code ?ordering=} accepts, alphabetically -- the order the 400 lists them in. */
    public static final Map<String, String> ORDERINGS = Map.of(
            "recent", "p.published_at DESC, p.created_at DESC",
            // Last edit rather than publication. Recording a view deliberately
            // does not touch updated_at, so reading a post cannot promote it.
            "updated", "p.updated_at DESC, p.created_at DESC",
            // The default order as the tie-break, so a page of all-zero counts
            // still reads newest first.
            "views", "p.view_count DESC, p.published_at DESC, p.created_at DESC");
    public static final List<String> ORDERING_NAMES = ORDERINGS.keySet().stream().sorted().toList();

    /** Everything, as the owner's statistics page counts it. */
    public static final PostQuery ALL = new PostQuery(false, null, "", null, null, "recent");

    public static PostQuery of(HttpServletRequest request) {
        String tag = QueryParams.trimmed(request.getParameter("tag"));
        LocalDate after = QueryParams.date(request.getParameter("published_after"), "published_after");
        LocalDate before = QueryParams.date(request.getParameter("published_before"), "published_before");

        String ordering = request.getParameter("ordering");
        if (ordering == null || ordering.isEmpty()) {
            ordering = "recent";
        } else {
            QueryParams.rejectUnknown(ordering, ORDERING_NAMES, "ordering");
        }

        if (!Viewer.isAuthenticated()) {
            return new PostQuery(true, null, tag, after, before, ordering);
        }
        String status = request.getParameter("status");
        if (status == null || status.isEmpty()) {
            status = null;
        } else {
            QueryParams.rejectUnknown(status, Post.STATUSES, "status");
        }
        return new PostQuery(false, status, tag, after, before, ordering);
    }

    public Sql.Where where() {
        Sql.Where where = new Sql.Where();
        if (publishedOnly) {
            where.and("p.status = :visibleStatus", "visibleStatus", Post.PUBLISHED);
        } else if (status != null) {
            where.and("p.status = :status", "status", status);
        }
        if (!tag.isEmpty()) {
            // Two posts may hold two spellings of one tag (each post dedupes
            // only its own list), and a filter naming either has to find both.
            where.and("EXISTS (SELECT 1 FROM unnest(p.tags) AS t(label) WHERE lower(t.label) = lower(:tag))",
                    "tag", tag);
        }
        // A draft has no published_at, so it is filtered by the date it shows:
        // created_at. Without the COALESCE every draft would drop out of a
        // range the moment one was applied.
        String day = "(COALESCE(p.published_at, p.created_at) AT TIME ZONE 'UTC')::date";
        if (after != null) {
            where.and(day + " >= :after", "after", after);
        }
        if (before != null) {
            where.and(day + " <= :before", "before", before);
        }
        return where;
    }

    /** ORDER BY, ending in the id so a page boundary can never split a tie. */
    public String orderBy() {
        return " ORDER BY " + ORDERINGS.get(ordering) + ", p.id DESC";
    }
}
