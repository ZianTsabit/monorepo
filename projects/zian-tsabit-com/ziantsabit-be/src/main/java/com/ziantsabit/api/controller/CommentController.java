package com.ziantsabit.api.controller;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ziantsabit.api.dto.ApiDocs;
import com.ziantsabit.api.dto.PageResponse;
import com.ziantsabit.api.model.Comment;
import com.ziantsabit.api.query.CommentQuery;
import com.ziantsabit.api.security.Throttle;
import com.ziantsabit.api.service.CommentService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Visitors' comments: listed per post with {@code ?post=<slug>}, written by
 * anyone, moderated by the owner.
 *
 * <p>Top-level rather than nested under a post because the admin console reads
 * comments across every post, which a nested route cannot express.
 */
@RestController
@RequestMapping("/api/comments")
@Tag(name = "comments")
public class CommentController {

    public static final int PAGE_SIZE = 20;

    private final CommentService service;
    private final Throttle commentThrottle;

    public CommentController(CommentService service,
            @Qualifier("commentThrottle") Throttle commentThrottle) {
        this.service = service;
        this.commentThrottle = commentThrottle;
    }

    @GetMapping
    @Operation(summary = "List comments, oldest first", parameters = {
            @Parameter(name = "post", in = ParameterIn.QUERY,
                    description = "Only comments on the post with this slug. This is how the public page asks "
                            + "for its thread."),
            @Parameter(name = "status", in = ParameterIn.QUERY, description = "Only comments with this status. "
                    + "Authenticated callers only: anonymous requests always see published comments and nothing "
                    + "else.", schema = @Schema(allowableValues = {"published", "hidden"})),
            @Parameter(name = "search", in = ParameterIn.QUERY,
                    description = "Match against the comment body or the name on it."),
            @Parameter(name = "ordering", in = ParameterIn.QUERY, description = "'oldest' (the default) is the "
                    + "order a thread is read in; 'newest' puts the most recent first.",
                    schema = @Schema(allowableValues = {"newest", "oldest"})),
            @Parameter(name = "page", in = ParameterIn.QUERY, schema = @Schema(type = "integer"))})
    public PageResponse<Comment> list(HttpServletRequest request) {
        CommentQuery query = CommentQuery.of(request);
        return PageResponse.of(request, PAGE_SIZE, service.count(query),
                (limit, offset) -> service.find(query, limit, offset));
    }

    /**
     * Post a comment. Open to anyone and therefore rate-limited -- reading a
     * thread is not, or a busy post would stop serving its own comments.
     */
    @PostMapping
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            content = @Content(schema = @Schema(implementation = ApiDocs.CommentInput.class)))
    public ResponseEntity<Comment> create(@RequestBody(required = false) Object body, HttpServletRequest request) {
        commentThrottle.check(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(body));
    }

    @GetMapping("/{id}")
    public Comment retrieve(@PathVariable String id, HttpServletRequest request) {
        return visible(id, request);
    }

    @PutMapping("/{id}")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            content = @Content(schema = @Schema(implementation = ApiDocs.CommentInput.class)))
    public Comment replace(@PathVariable String id, @RequestBody(required = false) Object body,
            HttpServletRequest request) {
        return service.update(visible(id, request), body, false);
    }

    @PatchMapping("/{id}")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            content = @Content(schema = @Schema(implementation = ApiDocs.CommentInput.class)))
    public Comment patch(@PathVariable String id, @RequestBody(required = false) Object body,
            HttpServletRequest request) {
        return service.update(visible(id, request), body, true);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id, HttpServletRequest request) {
        service.delete(visible(id, request));
        return ResponseEntity.noContent().build();
    }

    private Comment visible(String rawId, HttpServletRequest request) {
        return service.visible(CommentQuery.of(request), rawId);
    }
}
