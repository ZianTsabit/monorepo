package com.ziantsabit.api.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
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
 * <p>Each test starts from a clean state: no rows but the two seeded pages, no
 * users, and fresh rate-limit counters.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
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

    /** Anonymous. */
    protected Api client;

    /**
     * The pages as the migrations seeded them, taken before the first test in
     * this JVM can change them, and put back before every test.
     */
    private static List<Map<String, Object>> seededPages;

    @BeforeEach
    void resetState() {
        if (seededPages == null) {
            seededPages = jdbc.sql("SELECT key, data::text AS data FROM page_content").query().listOfRows();
        }
        jdbc.sql("""
                TRUNCATE comment, reaction, post_view_day, post, book,
                         app_user, spring_session, page_content RESTART IDENTITY CASCADE""").update();
        for (Map<String, Object> page : seededPages) {
            jdbc.sql("INSERT INTO page_content (key, data) VALUES (:key, CAST(:data AS jsonb))")
                    .param("key", page.get("key")).param("data", page.get("data")).update();
        }
        commentThrottle.reset();
        reactionThrottle.reset();
        storage.clear();
        client = anonymous();
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

    /** The owner, by basic auth, which needs no CSRF token. */
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

    /** Re-save a post through the service's save path, so slugs and publish stamps apply. */
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
                INSERT INTO comment (post_id, author_name, body, status, created_at, updated_at)
                VALUES (:post, :author, :body, :status, :now, :now) RETURNING id""")
                .param("post", post.id()).param("author", author).param("body", body).param("status", status)
                .param("now", Sql.timestamp(Sql.now()))
                .query(Long.class).single();
    }

    protected void reaction(Post post, String emoji, String visitor) {
        jdbc.sql("""
                INSERT INTO reaction (post_id, emoji, visitor, created_at)
                VALUES (:post, :emoji, :visitor, now())""")
                .param("post", post.id()).param("emoji", emoji).param("visitor", visitor).update();
    }

    protected void viewDay(Post post, LocalDate date, int count) {
        jdbc.sql("INSERT INTO post_view_day (post_id, date, count) VALUES (:post, :date, :count)")
                .param("post", post.id()).param("date", date).param("count", count).update();
    }

    /** Write columns straight to a row, bypassing the save path. */
    protected void update(String table, long id, Map<String, Object> columns) {
        for (Map.Entry<String, Object> column : columns.entrySet()) {
            Object value = column.getValue() instanceof Instant instant ? Sql.timestamp(instant) : column.getValue();
            jdbc.sql("UPDATE " + table + " SET " + column.getKey() + " = :value WHERE id = :id")
                    .param("value", value).param("id", id).update();
        }
    }

    /** One row as a column map, as stored now. */
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
        return jdbc.sql("SELECT tags FROM post WHERE id = :id").param("id", postId)
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
