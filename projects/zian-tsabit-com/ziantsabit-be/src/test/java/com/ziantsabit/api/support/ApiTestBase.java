package com.ziantsabit.api.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.ziantsabit.api.model.Book;
import com.ziantsabit.api.model.BookFields;
import com.ziantsabit.api.model.Post;
import com.ziantsabit.api.model.PostFields;
import com.ziantsabit.api.security.Throttle;
import com.ziantsabit.api.security.UserAccounts;
import com.ziantsabit.api.service.BookService;
import com.ziantsabit.api.service.PostService;
import com.ziantsabit.api.util.Sql;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every API test runs against the real server on a random port, backed by a
 * real Postgres (embedded, started once per JVM) with the real migrations.
 *
 * <p>Each test starts from the state the Django suite's TestCase gave it: no
 * rows but the two seeded pages, no users, and fresh rate-limit counters.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        // Fast hashing; the format and the code path are the production ones.
        "app.password-iterations=1000",
        // Pinned rather than taken from the defaults, so the CORS tests assert
        // the behaviour and not the environment.
        "app.cors-allowed-origins=" + ApiTestBase.SPA_ORIGIN,
        "app.max-upload-size=10485760",
})
@Import(TestStorageConfig.class)
public abstract class ApiTestBase {

    public static final String SPA_ORIGIN = "http://spa.test";
    public static final String USERNAME = "zian";
    public static final String PASSWORD = "pw-for-tests";

    private static final EmbeddedPostgres POSTGRES = startPostgres();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
    }

    private static EmbeddedPostgres startPostgres() {
        try {
            return EmbeddedPostgres.builder().start();
        } catch (IOException failed) {
            throw new UncheckedIOException("Could not start the embedded Postgres", failed);
        }
    }

    @Autowired
    protected JdbcClient jdbc;
    @Autowired
    protected JsonMapper json;
    @Autowired
    protected PostService postService;
    @Autowired
    protected BookService bookService;
    @Autowired
    protected UserAccounts accounts;
    @Autowired
    protected InMemoryStorage storage;
    @Autowired
    @Qualifier("commentThrottle")
    private Throttle commentThrottle;
    @Autowired
    @Qualifier("reactionThrottle")
    private Throttle reactionThrottle;
    @Autowired
    private Environment environment;
    @Autowired
    private DataSource dataSource;

    /** Anonymous. */
    protected Api client;

    @BeforeEach
    void resetState() {
        jdbc.sql("""
                TRUNCATE myapp_comment, myapp_reaction, myapp_postviewday, myapp_post, myapp_book,
                         auth_user, spring_session, myapp_pagecontent RESTART IDENTITY CASCADE""").update();
        // Plain JdbcTemplate: the page JSON is full of colons the named-parameter
        // parser would otherwise have opinions about.
        new JdbcTemplate(dataSource).execute(seedPages());
        commentThrottle.reset();
        reactionThrottle.reset();
        storage.clear();
        client = anonymous();
    }

    private static String seedPages() {
        try {
            return new ClassPathResource("db/migration/V2__seed_page_content.sql")
                    .getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    // --- callers ----------------------------------------------------------

    protected String base() {
        return "http://localhost:" + environment.getProperty("local.server.port");
    }

    protected Api anonymous() {
        return new Api(base(), json, false);
    }

    /** A browser: keeps cookies between requests, sends no credentials of its own. */
    protected Api browser() {
        return new Api(base(), json, true);
    }

    /** The owner, by basic auth -- the Django suite's force_authenticate. */
    protected Api owner() {
        return anonymous().basicAuth(USERNAME, PASSWORD);
    }

    protected void createUser() {
        accounts.create(USERNAME, PASSWORD);
    }

    // --- fixtures ---------------------------------------------------------

    protected Post post(String title) {
        return post(title, fields -> {
        });
    }

    protected Post post(String title, Consumer<PostFields> customise) {
        PostFields fields = new PostFields();
        fields.title = title;
        customise.accept(fields);
        return postService.save(null, fields);
    }

    protected Post published(String title) {
        return post(title, fields -> fields.status = Post.PUBLISHED);
    }

    protected Post published(String title, Consumer<PostFields> customise) {
        return post(title, fields -> {
            fields.status = Post.PUBLISHED;
            customise.accept(fields);
        });
    }

    /** Re-save a post through the model path, as Django's post.save() did. */
    protected Post resave(Post post, Consumer<PostFields> change) {
        PostFields fields = PostFields.of(post);
        change.accept(fields);
        return postService.save(post.id(), fields);
    }

    protected Book book(String title, String author) {
        return book(title, author, fields -> {
        });
    }

    protected Book book(String title, String author, Consumer<BookFields> customise) {
        BookFields fields = new BookFields();
        fields.title = title;
        fields.author = author;
        customise.accept(fields);
        return bookService.save(null, fields);
    }

    protected long comment(Post post, String author, String body) {
        return comment(post, author, body, "published");
    }

    protected long comment(Post post, String author, String body, String status) {
        return jdbc.sql("""
                INSERT INTO myapp_comment (post_id, author_name, body, status, created_at, updated_at)
                VALUES (:post, :author, :body, :status, :now, :now) RETURNING id""")
                .param("post", post.id()).param("author", author).param("body", body).param("status", status)
                .param("now", Sql.timestamp(Sql.now()))
                .query(Long.class).single();
    }

    protected void reaction(Post post, String emoji, String visitor) {
        jdbc.sql("""
                INSERT INTO myapp_reaction (post_id, emoji, visitor, created_at)
                VALUES (:post, :emoji, :visitor, now())""")
                .param("post", post.id()).param("emoji", emoji).param("visitor", visitor).update();
    }

    protected void viewDay(Post post, LocalDate date, int count) {
        jdbc.sql("INSERT INTO myapp_postviewday (post_id, date, count) VALUES (:post, :date, :count)")
                .param("post", post.id()).param("date", date).param("count", count).update();
    }

    /** Write columns straight to a row, bypassing the save path -- Django's queryset.update(). */
    protected void update(String table, long id, Map<String, Object> columns) {
        for (Map.Entry<String, Object> column : columns.entrySet()) {
            Object value = column.getValue() instanceof Instant instant ? Sql.timestamp(instant) : column.getValue();
            jdbc.sql("UPDATE " + table + " SET " + column.getKey() + " = :value WHERE id = :id")
                    .param("value", value).param("id", id).update();
        }
    }

    /** One row as a column map -- Django's refresh_from_db(). */
    protected Map<String, Object> row(String table, long id) {
        return jdbc.sql("SELECT * FROM " + table + " WHERE id = :id").param("id", id).query().singleRow();
    }

    protected boolean exists(String table, long id) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM " + table + " WHERE id = :id)").param("id", id)
                .query(Boolean.class).single();
    }

    protected long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    protected List<String> tagsOf(long postId) {
        return jdbc.sql("SELECT tags FROM myapp_post WHERE id = :id").param("id", postId)
                .query((row, n) -> Sql.strings(row, "tags")).single();
    }

    protected static LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }

    protected static Map<String, Object> body(Object... pairs) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            body.put((String) pairs[index], pairs[index + 1]);
        }
        return body;
    }
}
