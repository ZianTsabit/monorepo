package com.ziantsabit.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Request bodies, described for Swagger only.
 *
 * <p>The controllers take their bodies as plain JSON and validate them field by
 * field (see {@code Form}), because that is the only way to reproduce DRF's
 * error messages and its partial-update rules exactly. The cost is that the
 * schema generator cannot see what a body may contain -- so it is spelled out
 * here, and referenced from each write's {@code @RequestBody} annotation.
 * Keep these in step with the {@code read()} method of the matching service.
 */
public final class ApiDocs {

    private ApiDocs() {
    }

    @Schema(name = "PostRequest", description = "Every field is optional on PATCH. A blank or omitted slug is "
            + "generated from the title on create and left alone on update.")
    public record PostInput(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 200) String title,
            @Schema(maxLength = 220, pattern = "^[-a-zA-Z0-9_]+$") String slug,
            String excerpt,
            @Schema(description = "Markdown") String body,
            @Schema(format = "uri", maxLength = 500) String coverImageUrl,
            @Schema(maxLength = 200) String coverImageAlt,
            @Schema(description = "Free-form labels; trimmed, and deduped ignoring case") List<String> tags,
            @Schema(allowableValues = {"draft", "published"}, defaultValue = "draft") String status,
            @Schema(nullable = true, description = "Stamped with now when a post is published without one")
            Instant publishedAt,
            @Schema(defaultValue = "true") Boolean commentsEnabled,
            @Schema(defaultValue = "true") Boolean reactionsEnabled,
            @Schema(allowableValues = {"", "light", "dark", "rain"},
                    description = "Blank leaves the reader's own colour scheme alone") String theme) {
    }

    @Schema(name = "BookRequest", description = "Every field is optional on PATCH. Title and author together "
            + "must not match a book already in the catalogue, ignoring case.")
    public record BookInput(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 200) String title,
            @Schema(maxLength = 220, pattern = "^[-a-zA-Z0-9_]+$") String slug,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 200) String author,
            List<String> genres,
            @Schema(maxLength = 17, description = "ISBN-10 or ISBN-13; separators are stripped") String isbn,
            @Schema(nullable = true, minimum = "1450") Integer releaseYear,
            @Schema(description = "Markdown") String review,
            @Schema(format = "uri", maxLength = 500) String coverImageUrl,
            @Schema(maxLength = 200) String coverImageAlt,
            @Schema(allowableValues = {"draft", "published"}, defaultValue = "draft") String status) {
    }

    @Schema(name = "CommentRequest", description = "A visitor's comment is always published; only the owner "
            + "may choose a status.")
    public record CommentInput(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The post's slug") String post,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 80) String authorName,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 2000,
                    description = "Plain text, never rendered as Markdown") String body,
            @Schema(allowableValues = {"published", "hidden"}) String status) {
    }

    @Schema(name = "ReactionRequest")
    public record ReactionInput(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String emoji,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 64,
                    description = "The browser's own opaque token") String visitor) {
    }

    @Schema(name = "PageContentRequest")
    public record PageInput(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The page document. Missing "
                    + "keys are filled with defaults and unknown ones dropped.") Map<String, Object> data) {
    }

    @Schema(name = "LoginRequest")
    public record LoginInput(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String username,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "password") String password) {
    }

    @Schema(name = "ImageUploadRequest")
    public record ImageUploadInput(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string", format = "binary") String file) {
    }
}
