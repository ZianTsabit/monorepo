package com.ziantsabit.api.controller;

import java.util.List;

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
import com.ziantsabit.api.dto.LabelCount;
import com.ziantsabit.api.dto.PageResponse;
import com.ziantsabit.api.dto.PostStats;
import com.ziantsabit.api.dto.ReactionSummary;
import com.ziantsabit.api.dto.ViewCount;
import com.ziantsabit.api.model.Post;
import com.ziantsabit.api.query.PostQuery;
import com.ziantsabit.api.security.Throttle;
import com.ziantsabit.api.service.PostService;
import com.ziantsabit.api.service.PostStatsService;
import com.ziantsabit.api.service.ReactionService;
import com.ziantsabit.api.validation.QueryParams;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Posts, addressed by slug. Reads are open; writes need the owner, apart from
 * the two things a visitor may do to a published post -- count a read, and
 * react to it. Which routes need a login is decided in SecurityConfig.
 */
@RestController
@RequestMapping("/api/posts")
@Tag(name = "posts")
public class PostController {

    public static final int PAGE_SIZE = 20;

    private final PostService service;
    private final PostStatsService stats;
    private final ReactionService reactions;
    private final Throttle reactionThrottle;

    public PostController(PostService service, PostStatsService stats,
            ReactionService reactions, @Qualifier("reactionThrottle") Throttle reactionThrottle) {
        this.service = service;
        this.stats = stats;
        this.reactions = reactions;
        this.reactionThrottle = reactionThrottle;
    }

    @GetMapping
    @Operation(summary = "List posts, newest first", parameters = {
            @Parameter(name = "tag", in = ParameterIn.QUERY, description = "Only posts carrying this tag. "
                    + "Matched case-insensitively; an unused tag is an empty result, not an error."),
            @Parameter(name = "status", in = ParameterIn.QUERY, description = "Only posts with this status. "
                    + "Authenticated callers only: anonymous requests always see published posts and nothing else.",
                    schema = @Schema(allowableValues = {"draft", "published"})),
            @Parameter(name = "ordering", in = ParameterIn.QUERY, description = "'recent' (the default) is newest "
                    + "first; 'updated' is most-recently-edited first; 'views' is most-read first.",
                    schema = @Schema(allowableValues = {"recent", "updated", "views"})),
            @Parameter(name = "published_after", in = ParameterIn.QUERY, description = "Only posts dated on or "
                    + "after this day. The date compared is published_at, or created_at for a draft.",
                    schema = @Schema(type = "string", format = "date")),
            @Parameter(name = "published_before", in = ParameterIn.QUERY,
                    description = "Only posts dated on or before this day.",
                    schema = @Schema(type = "string", format = "date")),
            @Parameter(name = "page", in = ParameterIn.QUERY, schema = @Schema(type = "integer"))})
    public PageResponse<Post> list(HttpServletRequest request) {
        PostQuery query = PostQuery.of(request);
        return PageResponse.of(request, PAGE_SIZE, service.count(query),
                (limit, offset) -> service.find(query, limit, offset));
    }

    @PostMapping
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(schema = @Schema(implementation = ApiDocs.PostInput.class)))
    public ResponseEntity<Post> create(@RequestBody(required = false) Object body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(body));
    }

    @GetMapping("/stats")
    @Operation(summary = "Site-wide aggregates for the admin statistics page. Authenticated only.")
    public PostStats stats() {
        return stats.compute();
    }

    @GetMapping("/tags")
    @Operation(summary = "Every tag in use, with how many posts carry it, commonest first")
    public List<LabelCount> tags(HttpServletRequest request) {
        return service.tags(PostQuery.of(request));
    }

    @GetMapping("/{slug}")
    public Post retrieve(@PathVariable String slug, HttpServletRequest request) {
        return visible(slug, request);
    }

    @PutMapping("/{slug}")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(schema = @Schema(implementation = ApiDocs.PostInput.class)))
    public Post replace(@PathVariable String slug, @RequestBody(required = false) Object body,
            HttpServletRequest request) {
        return service.update(visible(slug, request), body, false);
    }

    @PatchMapping("/{slug}")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(schema = @Schema(implementation = ApiDocs.PostInput.class)))
    public Post patch(@PathVariable String slug, @RequestBody(required = false) Object body,
            HttpServletRequest request) {
        return service.update(visible(slug, request), body, true);
    }

    @DeleteMapping("/{slug}")
    public ResponseEntity<Void> delete(@PathVariable String slug, HttpServletRequest request) {
        service.delete(visible(slug, request));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{slug}/view")
    @Operation(summary = "Record one read of this post and return its new total. "
            + "Open to anonymous callers -- it is what the public detail page calls.")
    public ViewCount recordView(@PathVariable String slug, HttpServletRequest request) {
        // A visitor counting a draft gets the same 404 the detail route gives.
        return service.recordView(visible(slug, request));
    }

    @GetMapping("/{slug}/reactions")
    @Operation(summary = "Every emoji this post may be reacted with, its count, and whether the calling "
            + "browser picked it. Dense: an unused emoji still comes back, with a count of 0.",
            parameters = @Parameter(name = "visitor", in = ParameterIn.QUERY, description = "The browser's own "
                    + "opaque token. Omit it and every `reacted` is false; the counts are the same either way."))
    public ReactionSummary reactions(@PathVariable String slug, HttpServletRequest request) {
        reactionThrottle.check(request);
        // Stays open with reactions off: it is a count of things that already
        // happened, and a 404 would make the switch look like the post had gone.
        Post post = visible(slug, request);
        return reactions.summarise(post, QueryParams.trimmed(request.getParameter("visitor")));
    }

    @PostMapping("/{slug}/reactions")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            content = @Content(schema = @Schema(implementation = ApiDocs.ReactionInput.class)))
    @Operation(summary = "Toggle one emoji for one browser and return the whole bar again. Sending the same "
            + "emoji twice removes it.")
    public ReactionSummary react(@PathVariable String slug, @RequestBody(required = false) Object body,
            HttpServletRequest request) {
        reactionThrottle.check(request);
        return reactions.react(visible(slug, request), body);
    }

    private Post visible(String slug, HttpServletRequest request) {
        return service.visible(PostQuery.of(request), slug);
    }
}
