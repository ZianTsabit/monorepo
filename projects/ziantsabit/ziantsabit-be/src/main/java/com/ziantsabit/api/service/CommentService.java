package com.ziantsabit.api.service;

import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ziantsabit.api.exception.ApiException;
import com.ziantsabit.api.model.Comment;
import com.ziantsabit.api.model.Post;
import com.ziantsabit.api.query.CommentQuery;
import com.ziantsabit.api.repository.CommentRepository;
import com.ziantsabit.api.repository.PostRepository;
import com.ziantsabit.api.security.Viewer;
import com.ziantsabit.api.util.Sql;
import com.ziantsabit.api.validation.Form;

/** Validating and saving comments. */
@Service
public class CommentService {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    private final CommentRepository comments;
    private final PostRepository posts;

    public CommentService(CommentRepository comments, PostRepository posts) {
        this.comments = comments;
        this.posts = posts;
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
    public Comment create(Object body) {
        Form form = Form.of(body, false);
        Fields fields = read(form, new Fields());
        String status = Viewer.isAuthenticated() && fields.status != null ? fields.status : Comment.PUBLISHED;
        Instant now = Sql.now();
        return comments.get(comments.insert(fields.postId, fields.authorName, fields.body, status, now));
    }

    /** The owner's edit: hiding, restoring, or correcting a comment. */
    @Transactional
    public Comment update(Comment comment, Object body, boolean partial) {
        Fields fields = new Fields();
        fields.postId = comments.postIdOf(comment.id());
        fields.authorName = comment.authorName();
        fields.body = comment.body();
        fields.status = comment.status();
        read(Form.of(body, partial), fields);
        comments.update(comment.id(), fields.postId, fields.authorName, fields.body, fields.status, Sql.now());
        return comments.get(comment.id());
    }

    private static final class Fields {
        Long postId;
        String authorName;
        String body;
        String status;
    }

    private Fields read(Form form, Fields fields) {
        form.slugReference("post", true, posts::findBySlug).ifPresent(post -> {
            String refusal = refusal(post);
            if (refusal != null) {
                form.error("post", refusal);
            } else {
                fields.postId = post.id();
            }
        });
        form.text("author_name", true, false, 80).ifPresent(name -> {
            // Inner runs collapsed too, so a name padded out to look like a
            // heading in the thread cannot be.
            String collapsed = String.join(" ", WHITESPACE.split(name.strip()));
            if (collapsed.isEmpty()) {
                form.error("author_name", "Give a name to post under.");
            } else {
                fields.authorName = collapsed;
            }
        });
        // Only the ends are trimmed: the newlines inside are paragraphs.
        form.text("body", true, false, 2000).ifPresent(text -> {
            if (text.strip().isEmpty()) {
                form.error("body", "Write something first.");
            } else {
                fields.body = text.strip();
            }
        });
        form.choice("status", false, Comment.STATUSES, false).ifPresent(status -> fields.status = status);
        form.check();
        return fields;
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
