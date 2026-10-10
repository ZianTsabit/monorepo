package com.ziantsabit.api.repository;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.ziantsabit.api.util.Sql;

/** SQL for {@code myapp_reaction}. */
@Repository
public class ReactionRepository {

    private final JdbcClient jdbc;

    public ReactionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** How many reactions each emoji has on one post, retired emoji included. */
    public Map<String, Long> countsByEmoji(long postId) {
        return jdbc.sql("SELECT emoji, count(*) AS n FROM myapp_reaction WHERE post_id = :post GROUP BY emoji")
                .param("post", postId)
                .query((row, n) -> Map.entry(row.getString("emoji"), row.getLong("n")))
                .list().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /** The emoji one visitor has left on one post. */
    public Set<String> emojiOf(long postId, String visitor) {
        return Set.copyOf(jdbc.sql("SELECT emoji FROM myapp_reaction WHERE post_id = :post AND visitor = :visitor")
                .param("post", postId).param("visitor", visitor)
                .query(String.class).list());
    }

    /** Remove one reaction, returning whether there was one. */
    public boolean delete(long postId, String emoji, String visitor) {
        return jdbc.sql("DELETE FROM myapp_reaction WHERE post_id = :post AND emoji = :emoji AND visitor = :visitor")
                .param("post", postId).param("emoji", emoji).param("visitor", visitor).update() > 0;
    }

    /**
     * Add one reaction. When two taps race, the unique constraint lets one
     * insert win and the other is quietly a no-op -- the reaction is on, which
     * is what both asked for.
     */
    public void insert(long postId, String emoji, String visitor) {
        jdbc.sql("""
                INSERT INTO myapp_reaction (post_id, emoji, visitor, created_at)
                VALUES (:post, :emoji, :visitor, :now)
                ON CONFLICT ON CONSTRAINT unique_post_emoji_visitor DO NOTHING""")
                .param("post", postId).param("emoji", emoji).param("visitor", visitor)
                .param("now", Sql.timestamp(Sql.now()))
                .update();
    }
}
