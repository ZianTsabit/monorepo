package com.ziantsabit.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

/**
 * V1-V4 against the schema production actually had before Flyway: a
 * schema-only {@code pg_dump} of it, committed as {@code legacy-schema.sql}.
 *
 * <p>The rest of the suite only ever migrates an empty database, where V1
 * creates every table under the names it chooses. Production's tables were
 * there first, with different constraint and index names and a handful of
 * extra tables, so this is the test that says V4 works where it matters. It
 * replays production's real path: baseline at 0 on a database with no history,
 * then V1 to V4.
 */
class LegacySchemaMigrationTest {

    static EmbeddedPostgres postgres;
    static JdbcClient jdbc;

    @BeforeAll
    static void migrateTheLegacySchema() throws IOException, SQLException {
        postgres = EmbeddedPostgres.builder().start();
        DataSource dataSource = postgres.getPostgresDatabase();
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(legacySchema());
        }
        jdbc = JdbcClient.create(dataSource);

        // The dump includes the tables Flyway and Spring Session created later,
        // empty; take them away so the database is as it was before either.
        jdbc.sql("DROP TABLE IF EXISTS flyway_schema_history, spring_session_attributes, spring_session").update();

        jdbc.sql("""
                INSERT INTO auth_user (password, is_superuser, username, first_name, last_name, email, is_staff,
                                       is_active, date_joined)
                VALUES ('pbkdf2_sha256$1000$abcdefghijklmnopqrstuv$yH2VGHMchEp1vrLNWnm89deW1DEjt3ZQbjqugpX9tLo=',
                        true, 'owner', 'O', 'W', 'o@example.com', true, true, now())""").update();
        jdbc.sql("""
                INSERT INTO myapp_post (title, slug, excerpt, body, status, published_at, created_at, updated_at,
                                        cover_image_alt, cover_image_url, view_count, tags, comments_enabled,
                                        reactions_enabled, theme)
                VALUES ('Kept', 'kept', '', 'Body', 'published', now(), now(), now(), '', '', 7,
                        ARRAY['spring'], true, true, '')""").update();
        long post = jdbc.sql("SELECT id FROM myapp_post").query(Long.class).single();
        jdbc.sql("INSERT INTO myapp_postviewday (date, count, post_id) VALUES (current_date, 3, :p)")
                .param("p", post).update();
        jdbc.sql("""
                INSERT INTO myapp_comment (author_name, body, status, created_at, updated_at, post_id)
                VALUES ('Ada', 'Hi', 'published', now(), now(), :p)""").param("p", post).update();
        jdbc.sql("INSERT INTO myapp_reaction (emoji, visitor, created_at, post_id) VALUES ('👍', 'v1', now(), :p)")
                .param("p", post).update();
        jdbc.sql("""
                INSERT INTO myapp_book (title, slug, author, genres, isbn, release_year, review, cover_image_url,
                                        cover_image_alt, status, created_at, updated_at)
                VALUES ('A Book', 'a-book', 'An Author', ARRAY['Essays'], '', 2001, '', '', '', 'published',
                        now(), now())""").update();
        jdbc.sql("""
                INSERT INTO myapp_pagecontent (key, data, updated_at)
                VALUES ('cv', '{"name": "Edited by the owner"}', now())""").update();

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .placeholderReplacement(false)
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate();
    }

    /** The committed dump, minus psql meta-commands, which JDBC cannot run. */
    static String legacySchema() throws IOException {
        String dump = new ClassPathResource("legacy-schema.sql").getContentAsString(StandardCharsets.UTF_8);
        return dump.lines()
                .filter(line -> !line.startsWith("\\"))
                // pg_dump empties the search path for its own session; the
                // statements after it are schema-qualified, the test's are not.
                .filter(line -> !line.contains("set_config('search_path'"))
                .collect(Collectors.joining("\n"));
    }

    @AfterAll
    static void stop() throws IOException {
        postgres.close();
    }

    List<String> tables() {
        return jdbc.sql("SELECT tablename FROM pg_tables WHERE schemaname = 'public' ORDER BY tablename")
                .query(String.class).list();
    }

    @Test
    void everyMigrationApplied() {
        assertThat(jdbc.sql("SELECT max(version::int) FROM flyway_schema_history WHERE success AND version <> '0'")
                .query(Integer.class).single()).isEqualTo(4);
    }

    @Test
    void theTablesHaveTheirNewNamesAndNothingElseIsLeft() {
        assertThat(tables()).containsExactlyInAnyOrder("app_user", "book", "comment", "flyway_schema_history",
                "page_content", "post", "post_view_day", "reaction", "spring_session", "spring_session_attributes");
    }

    @Test
    void theRowsSurvived() {
        assertThat(jdbc.sql("SELECT view_count FROM post WHERE slug = 'kept'").query(Integer.class).single())
                .isEqualTo(7);
        assertThat(jdbc.sql("SELECT count FROM post_view_day v JOIN post p ON p.id = v.post_id WHERE p.slug = 'kept'")
                .query(Integer.class).single()).isEqualTo(3);
        assertThat(jdbc.sql("SELECT author_name FROM comment c JOIN post p ON p.id = c.post_id WHERE p.slug = 'kept'")
                .query(String.class).single()).isEqualTo("Ada");
        assertThat(jdbc.sql("SELECT visitor FROM reaction").query(String.class).single()).isEqualTo("v1");
        assertThat(jdbc.sql("SELECT author FROM book WHERE slug = 'a-book'").query(String.class).single())
                .isEqualTo("An Author");
        // V2's seed did not overwrite the owner's page.
        assertThat(jdbc.sql("SELECT data->>'name' FROM page_content WHERE key = 'cv'").query(String.class).single())
                .isEqualTo("Edited by the owner");
    }

    @Test
    void theAccountIsTrimmedAndItsHashTagged() {
        assertThat(jdbc.sql("""
                SELECT column_name FROM information_schema.columns
                 WHERE table_name = 'app_user' ORDER BY ordinal_position""").query(String.class).list())
                .containsExactlyInAnyOrder("id", "password", "last_login", "username", "enabled", "created_at");
        assertThat(jdbc.sql("SELECT password FROM app_user").query(String.class).single())
                .startsWith("{pbkdf2-legacy}pbkdf2_sha256$1000$");
        assertThat(jdbc.sql("SELECT enabled FROM app_user").query(Boolean.class).single()).isTrue();
    }

    @Test
    void constraintsAndIndexesHaveTheirNewNames() {
        List<String> constraints = jdbc.sql("""
                SELECT conname FROM pg_constraint c JOIN pg_namespace n ON n.oid = c.connamespace
                 WHERE n.nspname = 'public' AND contype IN ('p', 'u', 'f', 'c')
                   AND conrelid::regclass::text NOT LIKE 'spring_session%'
                   AND conrelid::regclass::text <> 'flyway_schema_history'""").query(String.class).list();
        assertThat(constraints).allSatisfy(name -> assertThat(name).doesNotStartWith("myapp_").doesNotStartWith("auth_"))
                .contains("post_pkey", "post_slug_key", "comment_post_fkey", "reaction_post_emoji_visitor_key",
                        "post_view_day_post_date_key", "app_user_username_key");
        List<String> indexes = jdbc.sql("SELECT indexname FROM pg_indexes WHERE schemaname = 'public'")
                .query(String.class).list();
        assertThat(indexes).noneMatch(name -> name.startsWith("myapp_") || name.startsWith("auth_"))
                .contains("post_tags_gin", "book_genres_gin", "comment_post_status_created_idx");
    }

    @Test
    void deletingAPostTakesItsChildrenWithIt() {
        jdbc.sql("""
                INSERT INTO post (title, slug, excerpt, body, status, cover_image_alt, cover_image_url, tags,
                                  comments_enabled, reactions_enabled, theme)
                VALUES ('Doomed', 'doomed', '', '', 'draft', '', '', '{}', true, true, '')""").update();
        long doomed = jdbc.sql("SELECT id FROM post WHERE slug = 'doomed'").query(Long.class).single();
        jdbc.sql("INSERT INTO comment (author_name, body, status, post_id) VALUES ('x', 'y', 'published', :p)")
                .param("p", doomed).update();
        jdbc.sql("INSERT INTO post_view_day (date, post_id) VALUES (current_date, :p)").param("p", doomed).update();

        jdbc.sql("DELETE FROM post WHERE id = :p").param("p", doomed).update();

        assertThat(jdbc.sql("SELECT count(*) FROM comment WHERE post_id = :p").param("p", doomed)
                .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM post_view_day WHERE post_id = :p").param("p", doomed)
                .query(Long.class).single()).isZero();
    }

    @Test
    void newRowsGetTheirIdsFromTheRenamedSequences() {
        jdbc.sql("""
                INSERT INTO book (title, slug, author, genres, isbn, review, cover_image_url, cover_image_alt, status)
                VALUES ('Second', 'second', 'Someone', '{}', '', '', '', '', 'draft')""").update();
        assertThat(jdbc.sql("SELECT id FROM book WHERE slug = 'second'").query(Long.class).single())
                .isGreaterThan(jdbc.sql("SELECT id FROM book WHERE slug = 'a-book'").query(Long.class).single());
        assertThat(jdbc.sql("SELECT pg_get_serial_sequence('book', 'id')").query(String.class).single())
                .isEqualTo("public.book_id_seq");
    }
}
