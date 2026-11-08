package com.ziantsabit.api.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ziantsabit.api.dto.LabelCount;
import com.ziantsabit.api.dto.PostRequest;
import com.ziantsabit.api.dto.ViewCount;
import com.ziantsabit.api.exception.ApiException;
import com.ziantsabit.api.model.Post;
import com.ziantsabit.api.model.PostFields;
import com.ziantsabit.api.query.PostQuery;
import com.ziantsabit.api.repository.PostRepository;
import com.ziantsabit.api.util.Labels;
import com.ziantsabit.api.util.Slugs;
import com.ziantsabit.api.util.Sql;
import com.ziantsabit.api.validation.RequestBodies;

import tools.jackson.databind.JsonNode;

/** Validating and saving posts. */
@Service
public class PostService {

    // Long enough to be the whole vocabulary of a personal site, short enough
    // that a runaway list of typo'd labels cannot become the biggest response.
    public static final int LABEL_LIMIT = 100;
    public static final String SLUG_TAKEN = "A post with this slug already exists.";

    private final PostRepository posts;
    private final RequestBodies bodies;
    private final Clock clock;

    public PostService(PostRepository posts, RequestBodies bodies, Clock clock) {
        this.posts = posts;
        this.bodies = bodies;
        this.clock = clock;
    }

    public long count(PostQuery query) {
        return posts.count(query);
    }

    public List<Post> find(PostQuery query, int limit, long offset) {
        return posts.find(query, limit, offset);
    }

    /**
     * Every tag in use with how many posts carry it -- what the feed's filter
     * offers, since free text has no enum to read options off. Scoped like the
     * list, so a visitor is never offered a draft's tag.
     */
    public List<LabelCount> tags(PostQuery query) {
        return Labels.counts(posts.tagSpellings(query), LABEL_LIMIT);
    }

    /** The post with this slug, if the request may see it -- else the detail route's 404. */
    public Post visible(PostQuery query, String slug) {
        return posts.findBySlug(query, slug)
                .orElseThrow(() -> ApiException.notFound("No Post matches the given query."));
    }

    /** Count one read of a post, in its running total and in today's tally. */
    @Transactional
    public ViewCount recordView(Post post) {
        long views = posts.recordView(post.id(), LocalDate.now(clock.withZone(ZoneOffset.UTC)));
        return new ViewCount(post.slug(), views);
    }

    @Transactional
    public void delete(Post post) {
        posts.delete(post.id());
    }

    @Transactional
    public Post create(JsonNode body) {
        PostRequest request = bodies.read(body, PostRequest.class);
        return save(null, fields(request, null));
    }

    /** PATCH: the body is merged onto the stored post, so a field left out keeps its value. */
    @Transactional
    public Post update(Post post, JsonNode body) {
        PostRequest request = bodies.patch(PostRequest.of(post), body, PostRequest.class);
        return save(post.id(), fields(request, post.id()));
    }

    /**
     * Store a post, filling in what a form may leave blank.
     *
     * <p>Two rules live here rather than in the form reader, so they hold for
     * every write -- the test fixtures included:
     * <ul>
     *   <li><b>A blank slug is derived from the title</b> and deduped with
     *       {@code -2}, {@code -3}. It stays writable so a URL can be pinned.
     *   <li><b>Publishing without a date stamps it now.</b> The feed is ordered
     *       newest-published first, and Postgres sorts a null date to the top
     *       of a descending order, so a published post with none would sit
     *       above everything.
     * </ul>
     */
    @Transactional
    public Post save(Long id, PostFields fields) {
        Instant now = Sql.now();
        fields.tags = Labels.clean(fields.tags);
        if (fields.slug == null || fields.slug.isEmpty()) {
            String base = Slugs.slugify(fields.title);
            fields.slug = Slugs.unique(base.isEmpty() ? "post" : base, 220, slug -> posts.slugTaken(slug, id));
        }
        if (Post.PUBLISHED.equals(fields.status) && fields.publishedAt == null) {
            fields.publishedAt = now;
        }
        try {
            if (id == null) {
                return posts.get(posts.insert(fields, now));
            }
            posts.update(id, fields, now);
            return posts.get(id);
        } catch (DuplicateKeyException race) {
            // Two writes claimed the same slug between the check and the insert.
            throw ApiException.field("slug", SLUG_TAKEN);
        }
    }

    /** A validated request as fields to store, after the checks that need the database. */
    private PostFields fields(PostRequest request, Long id) {
        if (request.slug() != null) {
            if (request.slug().isEmpty()) {
                // Omitting the key is how "generate one" is asked for.
                throw ApiException.field("slug", "Leave slug out entirely to have it generated from the title.");
            }
            if (posts.slugTaken(request.slug(), id)) {
                throw ApiException.field("slug", SLUG_TAKEN);
            }
        }
        PostFields fields = new PostFields();
        fields.title = request.title().strip();
        fields.slug = request.slug() == null ? "" : request.slug();
        fields.excerpt = orBlank(request.excerpt());
        fields.body = orBlank(request.body());
        fields.coverImageUrl = orBlank(request.coverImageUrl());
        fields.coverImageAlt = orBlank(request.coverImageAlt());
        // Cleaned here as well as on save, so the response to a write is what
        // was stored rather than what was sent.
        fields.tags = Labels.clean(request.tags() == null ? List.of() : request.tags());
        if (request.status() != null) {
            fields.status = request.status();
        }
        fields.publishedAt = request.publishedAt();
        if (request.commentsEnabled() != null) {
            fields.commentsEnabled = request.commentsEnabled();
        }
        if (request.reactionsEnabled() != null) {
            fields.reactionsEnabled = request.reactionsEnabled();
        }
        fields.theme = orBlank(request.theme());
        return fields;
    }

    private static String orBlank(String value) {
        return value == null ? "" : value.strip();
    }
}
