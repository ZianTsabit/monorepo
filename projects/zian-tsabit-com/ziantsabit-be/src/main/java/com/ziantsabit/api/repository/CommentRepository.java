package com.ziantsabit.api.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.ziantsabit.api.model.Comment;
import com.ziantsabit.api.query.CommentQuery;
import com.ziantsabit.api.util.Sql;

/** SQL for {@code myapp_comment}, always joined to its post for the slug and title. */
@Repository
public class CommentRepository {

    private static final String SELECT = """
            SELECT c.*, p.slug AS post_slug, p.title AS post_title
              FROM myapp_comment c JOIN myapp_post p ON p.id = c.post_id""";

    private final JdbcClient jdbc;

    public CommentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long count(CommentQuery query) {
        return jdbc.sql("SELECT count(*) FROM myapp_comment c JOIN myapp_post p ON p.id = c.post_id"
                        + query.where().sql())
                .params(query.where().params()).query(Long.class).single();
    }

    public List<Comment> find(CommentQuery query, int limit, long offset) {
        return jdbc.sql(SELECT + query.where().sql() + query.orderBy() + " LIMIT :limit OFFSET :offset")
                .params(query.where().params()).param("limit", limit).param("offset", offset)
                .query(CommentRepository::comment).list();
    }

    public Optional<Comment> findById(CommentQuery query, long id) {
        Sql.Where where = query.where();
        return jdbc.sql(SELECT + where.sql() + (where.sql().isEmpty() ? " WHERE " : " AND ") + "c.id = :id")
                .params(where.params()).param("id", id)
                .query(CommentRepository::comment).optional();
    }

    public Comment get(long id) {
        return jdbc.sql(SELECT + " WHERE c.id = :id").param("id", id).query(CommentRepository::comment).single();
    }

    public long insert(long postId, String authorName, String body, String status, Instant now) {
        return jdbc.sql("""
                INSERT INTO myapp_comment (post_id, author_name, body, status, created_at, updated_at)
                VALUES (:post, :authorName, :body, :status, :now, :now)
                RETURNING id""")
                .param("post", postId).param("authorName", authorName).param("body", body)
                .param("status", status).param("now", Sql.timestamp(now))
                .query(Long.class).single();
    }

    public void update(long id, long postId, String authorName, String body, String status, Instant now) {
        jdbc.sql("""
                UPDATE myapp_comment
                   SET post_id = :post, author_name = :authorName, body = :body, status = :status,
                       updated_at = :now
                 WHERE id = :id""")
                .param("post", postId).param("authorName", authorName).param("body", body)
                .param("status", status).param("now", Sql.timestamp(now)).param("id", id)
                .update();
    }

    public void delete(long id) {
        jdbc.sql("DELETE FROM myapp_comment WHERE id = :id").param("id", id).update();
    }

    public long postIdOf(long commentId) {
        return jdbc.sql("SELECT post_id FROM myapp_comment WHERE id = :id").param("id", commentId)
                .query(Long.class).single();
    }

    private static Comment comment(ResultSet row, int rowNum) throws SQLException {
        return new Comment(
                row.getLong("id"),
                row.getString("post_slug"),
                row.getString("post_title"),
                row.getString("author_name"),
                row.getString("body"),
                row.getString("status"),
                Sql.instant(row, "created_at"),
                Sql.instant(row, "updated_at"));
    }
}
