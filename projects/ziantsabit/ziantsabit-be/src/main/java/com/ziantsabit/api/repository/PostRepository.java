package com.ziantsabit.api.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.ziantsabit.api.dto.LabelCount;
import com.ziantsabit.api.dto.PostStats;
import com.ziantsabit.api.model.Post;
import com.ziantsabit.api.model.PostFields;
import com.ziantsabit.api.query.PostQuery;
import com.ziantsabit.api.util.Sql;

/** SQL for {@code myapp_post} and the per-day view tally beside it. */
@Repository
public class PostRepository {

    // The visible comment count rides along with every post read, as a
    // correlated subquery rather than a join + GROUP BY: grouping would need
    // every column restated, and a join filtered on comment status would drop
    // posts with no visible comments off the feed entirely.
    private static final String SELECT = """
            SELECT p.*,
                   (SELECT count(*) FROM myapp_comment c
                     WHERE c.post_id = p.id AND c.status = 'published') AS comment_count
              FROM myapp_post p""";

    private final JdbcClient jdbc;

    public PostRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long count(PostQuery query) {
        Sql.Where where = query.where();
        return jdbc.sql("SELECT count(*) FROM myapp_post p" + where.sql())
                .params(where.params()).query(Long.class).single();
    }

    public List<Post> find(PostQuery query, int limit, long offset) {
        Sql.Where where = query.where();
        return jdbc.sql(SELECT + where.sql() + query.orderBy() + " LIMIT :limit OFFSET :offset")
                .params(where.params()).param("limit", limit).param("offset", offset)
                .query(PostRepository::post).list();
    }

    public Optional<Post> findBySlug(PostQuery query, String slug) {
        Sql.Where where = query.where().and("p.slug = :slug", "slug", slug);
        return jdbc.sql(SELECT + where.sql()).params(where.params()).query(PostRepository::post).optional();
    }

    public Optional<Post> findBySlug(String slug) {
        return findBySlug(PostQuery.ALL, slug);
    }

    public Post get(long id) {
        return jdbc.sql(SELECT + " WHERE p.id = :id").param("id", id).query(PostRepository::post).single();
    }

    public boolean slugTaken(String slug, Long excludeId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM myapp_post WHERE slug = :slug AND id IS DISTINCT FROM :id)")
                .param("slug", slug).param("id", excludeId).query(Boolean.class).single();
    }

    public long insert(PostFields post, Instant now) {
        return jdbc.sql("""
                INSERT INTO myapp_post (title, slug, excerpt, body, cover_image_url, cover_image_alt, tags,
                                        status, published_at, comments_enabled, reactions_enabled, theme,
                                        view_count, created_at, updated_at)
                VALUES (:title, :slug, :excerpt, :body, :coverImageUrl, :coverImageAlt, :tags,
                        :status, :publishedAt, :commentsEnabled, :reactionsEnabled, :theme,
                        0, :now, :now)
                RETURNING id""")
                .params(columns(post)).param("now", Sql.timestamp(now))
                .query(Long.class).single();
    }

    public void update(long id, PostFields post, Instant now) {
        // view_count is not here: it moves only through recordView, or an edit
        // would write back whatever number the form loaded and undo every read
        // since.
        jdbc.sql("""
                UPDATE myapp_post
                   SET title = :title, slug = :slug, excerpt = :excerpt, body = :body,
                       cover_image_url = :coverImageUrl, cover_image_alt = :coverImageAlt, tags = :tags,
                       status = :status, published_at = :publishedAt, comments_enabled = :commentsEnabled,
                       reactions_enabled = :reactionsEnabled, theme = :theme, updated_at = :now
                 WHERE id = :id""")
                .params(columns(post)).param("now", Sql.timestamp(now)).param("id", id)
                .update();
    }

    /**
     * Delete a post and everything hanging off it. The children go first and
     * explicitly: the foreign keys Django created have no ON DELETE CASCADE.
     */
    public void delete(long id) {
        for (String child : List.of("myapp_comment", "myapp_reaction", "myapp_postviewday")) {
            jdbc.sql("DELETE FROM " + child + " WHERE post_id = :id").param("id", id).update();
        }
        jdbc.sql("DELETE FROM myapp_post WHERE id = :id").param("id", id).update();
    }

    /**
     * One more read, as a single UPDATE: concurrent readers cannot each write
     * back the number they loaded, and updated_at is left alone -- a read is
     * not an edit.
     */
    public long recordView(long id, LocalDate day) {
        long views = jdbc.sql("UPDATE myapp_post SET view_count = view_count + 1 WHERE id = :id RETURNING view_count")
                .param("id", id).query(Long.class).single();
        // The same read filed under its day, for the statistics page's chart.
        // The unique (post, date) constraint makes two first-reads-of-the-day
        // racing each other land on one row.
        jdbc.sql("""
                INSERT INTO myapp_postviewday (post_id, date, count) VALUES (:id, :day, 1)
                ON CONFLICT (post_id, date) DO UPDATE SET count = myapp_postviewday.count + 1""")
                .param("id", id).param("day", day).update();
        return views;
    }

    /** Every spelling of every tag on the posts {@code query} can see, with counts. */
    public List<LabelCount> tagSpellings(PostQuery query) {
        Sql.Where where = query.where();
        return jdbc.sql("SELECT t.label AS name, count(*) AS count FROM myapp_post p "
                        + "CROSS JOIN LATERAL unnest(p.tags) AS t(label)" + where.sql() + " GROUP BY t.label")
                .params(where.params())
                .query((row, n) -> new LabelCount(row.getString("name"), row.getLong("count"))).list();
    }

    // --- statistics -----------------------------------------------------

    public record Totals(long total, long published, long totalViews, double averageViews) {
    }

    public Totals totals() {
        return jdbc.sql("""
                SELECT count(*) AS total,
                       count(*) FILTER (WHERE status = 'published') AS published,
                       COALESCE(sum(view_count), 0) AS total_views,
                       COALESCE(avg(view_count) FILTER (WHERE status = 'published'), 0) AS average_views
                  FROM myapp_post""")
                .query((row, n) -> new Totals(row.getLong("total"), row.getLong("published"),
                        row.getLong("total_views"), row.getDouble("average_views")))
                .single();
    }

    public Optional<Instant> firstPublishedAt() {
        // min() over no rows is one row holding NULL, which single() refuses.
        return jdbc.sql("""
                SELECT min(published_at) FROM myapp_post
                 WHERE status = 'published' AND published_at IS NOT NULL""")
                .query((row, n) -> Optional.ofNullable(Sql.instant(row, "min"))).single();
    }

    public List<PostStats.MostRead> mostRead(int limit) {
        return jdbc.sql("""
                SELECT slug, title, status, view_count FROM myapp_post
                 WHERE view_count > 0
                 ORDER BY view_count DESC, published_at DESC, created_at DESC
                 LIMIT :limit""")
                .param("limit", limit)
                .query((row, n) -> new PostStats.MostRead(row.getString("slug"), row.getString("title"),
                        row.getString("status"), row.getLong("view_count")))
                .list();
    }

    public List<PostStats.MonthCount> publishedByMonth() {
        return jdbc.sql("""
                SELECT to_char(date_trunc('month', published_at AT TIME ZONE 'UTC'), 'YYYY-MM') AS month,
                       count(*) AS count
                  FROM myapp_post
                 WHERE status = 'published' AND published_at IS NOT NULL
                 GROUP BY 1 ORDER BY 1""")
                .query((row, n) -> new PostStats.MonthCount(row.getString("month"), row.getLong("count")))
                .list();
    }

    public Map<LocalDate, Long> viewsByDay(LocalDate from, LocalDate to) {
        return jdbc.sql("""
                SELECT date, sum(count) AS count FROM myapp_postviewday
                 WHERE date BETWEEN :from AND :to GROUP BY date""")
                .param("from", from).param("to", to)
                .query((row, n) -> Map.entry(row.getObject("date", LocalDate.class), row.getLong("count")))
                .list().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    // --- mapping ----------------------------------------------------------

    private static Map<String, Object> columns(PostFields post) {
        // A HashMap rather than Map.of(): published_at is legitimately null.
        Map<String, Object> columns = new HashMap<>();
        columns.put("title", post.title);
        columns.put("slug", post.slug);
        columns.put("excerpt", post.excerpt);
        columns.put("body", post.body);
        columns.put("coverImageUrl", post.coverImageUrl);
        columns.put("coverImageAlt", post.coverImageAlt);
        columns.put("tags", Sql.textArray(post.tags));
        columns.put("status", post.status);
        columns.put("publishedAt", Sql.timestamp(post.publishedAt));
        columns.put("commentsEnabled", post.commentsEnabled);
        columns.put("reactionsEnabled", post.reactionsEnabled);
        columns.put("theme", post.theme);
        return columns;
    }

    private static Post post(ResultSet row, int rowNum) throws SQLException {
        return new Post(
                row.getLong("id"),
                row.getString("title"),
                row.getString("slug"),
                row.getString("excerpt"),
                row.getString("body"),
                row.getString("cover_image_url"),
                row.getString("cover_image_alt"),
                Sql.strings(row, "tags"),
                row.getString("status"),
                Sql.instant(row, "published_at"),
                row.getBoolean("comments_enabled"),
                row.getBoolean("reactions_enabled"),
                row.getString("theme"),
                row.getLong("view_count"),
                row.getLong("comment_count"),
                Sql.instant(row, "created_at"),
                Sql.instant(row, "updated_at"));
    }
}
