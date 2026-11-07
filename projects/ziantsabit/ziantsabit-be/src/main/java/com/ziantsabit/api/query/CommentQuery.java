package com.ziantsabit.api.query;

import java.util.List;
import java.util.Map;

import com.ziantsabit.api.model.Comment;
import com.ziantsabit.api.security.Viewer;
import com.ziantsabit.api.util.Sql;
import com.ziantsabit.api.validation.QueryParams;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Which comments a request may see. Query params: {@code ?post=<slug>},
 * {@code ?status=} (the owner only), {@code ?search=} (body or name) and
 * {@code ?ordering=oldest|newest}.
 */
public record CommentQuery(Sql.Where where, String orderBy) {

    /** "oldest" is the order a thread is read in; "newest" is for the admin. */
    public static final Map<String, String> ORDERINGS = Map.of(
            "oldest", "c.created_at ASC, c.id ASC",
            "newest", "c.created_at DESC, c.id DESC");
    public static final List<String> ORDERING_NAMES = ORDERINGS.keySet().stream().sorted().toList();

    public static CommentQuery of(HttpServletRequest request) {
        Sql.Where where = new Sql.Where();
        String search = QueryParams.trimmed(request.getParameter("search"));
        if (!search.isEmpty()) {
            where.and("(c.body ILIKE :search OR c.author_name ILIKE :search)", "search",
                    Sql.containing(search));
        }
        String ordering = request.getParameter("ordering");
        if (ordering == null || ordering.isEmpty()) {
            ordering = "oldest";
        } else {
            QueryParams.rejectUnknown(ordering, ORDERING_NAMES, "ordering");
        }
        String post = QueryParams.trimmed(request.getParameter("post"));
        if (!post.isEmpty()) {
            where.and("p.slug = :post", "post", post);
        }
        if (!Viewer.isAuthenticated()) {
            // Two conditions: a hidden comment is invisible, and so is every
            // comment on a post that is not itself public -- or the thread
            // would confirm an unpublished post exists.
            where.and("c.status = 'published'").and("p.status = 'published'");
        } else {
            String status = request.getParameter("status");
            if (status != null && !status.isEmpty()) {
                QueryParams.rejectUnknown(status, Comment.STATUSES, "status");
                where.and("c.status = :status", "status", status);
            }
        }
        return new CommentQuery(where, " ORDER BY " + ORDERINGS.get(ordering));
    }
}
