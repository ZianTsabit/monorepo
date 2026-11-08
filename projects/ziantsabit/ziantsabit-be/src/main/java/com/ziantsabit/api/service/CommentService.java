package com.ziantsabit.api.service;

import java.util.List;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ziantsabit.api.dto.CommentRequest;
import com.ziantsabit.api.exception.ApiException;
import com.ziantsabit.api.model.Comment;
import com.ziantsabit.api.model.Post;
import com.ziantsabit.api.query.CommentQuery;
import com.ziantsabit.api.repository.CommentRepository;
import com.ziantsabit.api.repository.PostRepository;
import com.ziantsabit.api.security.Viewer;
import com.ziantsabit.api.util.Sql;
import com.ziantsabit.api.validation.RequestBodies;

import tools.jackson.databind.JsonNode;

/** Validating and saving comments. */
@Service
public class CommentService {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    private final CommentRepository comments;
    private final PostRepository posts;
    private final RequestBodies bodies;

    public CommentService(CommentRepository comments, PostRepository posts, RequestBodies bodies) {
        this.comments = comments;
        this.posts = posts;
        this.bodies = bodies;
    }

    public long count(CommentQuery query) {
        return comments.count(query);
    }

    public List<Comment> find(CommentQuery query, int limit, long offset) {
        return comments.find(query, limit, offset);
    }

    /** One comment by id, if the request may see it. A non-numeric id is a 404 too. */
    public Comment visible(CommentQuery query, String rawId) {
        long id;
        try {
            id = Long.parseLong(rawId);
        } catch (NumberFormatException notAnId) {
            throw notFound();
        }
        return comments.findById(query, id).orElseThrow(CommentService::notFound);
    }

    @Transactional
    public void delete(Comment comment) {
        comments.delete(comment.id());
    }

    private static ApiException notFound() {
        return ApiException.notFound("No Comment matches the given query.");
    }

    /**
     * A new comment. A visitor's is always published, whatever status they
     * sent -- choosing your own moderation state is wrong whatever you choose.
     * The owner may file one hidden.
     */
    @Transactional
    public Comment create(JsonNode body) {
        CommentRequest request = bodies.read(body, CommentRequest.class);
        long postId = postIdFor(request.post());
        String status = Viewer.isAuthenticated() && request.status() != null ? request.status() : Comment.PUBLISHED;
        return comments.get(comments.insert(postId, authorName(request), request.body().strip(), status, Sql.now()));
    }

    /** The owner's edit: hiding, restoring, or correcting a comment. A PATCH, merged onto the stored one. */
    @Transactional
    public Comment update(Comment comment, JsonNode body) {
        CommentRequest request = bodies.patch(CommentRequest.of(comment), body, CommentRequest.class);
        long postId = postIdFor(request.post());
        comments.update(comment.id(), postId, authorName(request), request.body().strip(),
                request.status() == null ? comment.status() : request.status(), Sql.now());
        return comments.get(comment.id());
    }

    private long postIdFor(String slug) {
        Post post = posts.findBySlug(slug)
                .orElseThrow(() -> ApiException.field("post", "No post found with that slug."));
        String refusal = refusal(post);
        if (refusal != null) {
            throw ApiException.field("post", refusal);
        }
        return post.id();
    }

    /**
     * Inner runs of whitespace collapsed too, so a name padded out to look
     * like a heading in the thread cannot be.
     */
    private static String authorName(CommentRequest request) {
        return String.join(" ", WHITESPACE.split(request.authorName().strip()));
    }

    /**
     * Why a visitor may not comment on this post, or null if they may.
     *
     * <p>A draft is answered as if it did not exist, so a guessed slug confirms
     * nothing. A closed thread is a post the visitor is looking at, so that
     * message says plainly what happened. The owner is exempt from both.
     */
    private static String refusal(Post post) {
        if (Viewer.isAuthenticated()) {
            return null;
        }
        if (!Post.PUBLISHED.equals(post.status())) {
            return "No post found with that slug.";
        }
        if (!post.commentsEnabled()) {
            return "Comments are closed on this post.";
        }
        return null;
    }
}
