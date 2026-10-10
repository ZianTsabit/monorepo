package com.ziantsabit.api.controller;

import java.util.List;

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
import com.ziantsabit.api.model.Book;
import com.ziantsabit.api.query.BookQuery;
import com.ziantsabit.api.service.BookService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;

/** The book catalogue, addressed by slug. Reads are open; writes need the owner. */
@RestController
@RequestMapping("/api/books")
@Tag(name = "books")
public class BookController {

    /**
     * Shorter than the site-wide 20: {@code /books} is a grid of covers, and 12
     * divides into 6x2, 4x3 or 3x4 with no ragged last row at any likely width.
     * {@code BOOKS_PAGE_SIZE} in the SPA's {@code books.ts} is the same number,
     * and both page counts derive from it -- change the two together.
     */
    public static final int PAGE_SIZE = 12;

    private final BookService service;

    public BookController(BookService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "List the catalogue, most recently added first", parameters = {
            @Parameter(name = "genre", in = ParameterIn.QUERY, description = "Only books carrying this genre. "
                    + "Matched case-insensitively."),
            @Parameter(name = "search", in = ParameterIn.QUERY, description = "Match against title, author or ISBN."),
            @Parameter(name = "status", in = ParameterIn.QUERY, description = "Only books with this status. "
                    + "Authenticated callers only: anonymous requests always see published books and nothing else.",
                    schema = @Schema(allowableValues = {"draft", "published"})),
            @Parameter(name = "ordering", in = ParameterIn.QUERY, description = "'recent' (the default) is "
                    + "most-recently added first; 'title' and 'author' are A-Z; 'year' is newest release first, "
                    + "with undated books last.",
                    schema = @Schema(allowableValues = {"author", "recent", "title", "year"})),
            @Parameter(name = "page", in = ParameterIn.QUERY, schema = @Schema(type = "integer"))})
    public PageResponse<Book> list(HttpServletRequest request) {
        BookQuery query = BookQuery.of(request);
        return PageResponse.of(request, PAGE_SIZE, service.count(query),
                (limit, offset) -> service.find(query, limit, offset));
    }

    @PostMapping
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            content = @Content(schema = @Schema(implementation = ApiDocs.BookInput.class)))
    public ResponseEntity<Book> create(@RequestBody(required = false) Object body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(body));
    }

    @GetMapping("/genres")
    @Operation(summary = "Every genre in use, with how many books carry it, commonest first")
    public List<LabelCount> genres(HttpServletRequest request) {
        return service.genres(BookQuery.of(request));
    }

    @GetMapping("/{slug}")
    public Book retrieve(@PathVariable String slug, HttpServletRequest request) {
        return visible(slug, request);
    }

    @PutMapping("/{slug}")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            content = @Content(schema = @Schema(implementation = ApiDocs.BookInput.class)))
    public Book replace(@PathVariable String slug, @RequestBody(required = false) Object body,
            HttpServletRequest request) {
        return service.update(visible(slug, request), body, false);
    }

    @PatchMapping("/{slug}")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            content = @Content(schema = @Schema(implementation = ApiDocs.BookInput.class)))
    public Book patch(@PathVariable String slug, @RequestBody(required = false) Object body,
            HttpServletRequest request) {
        return service.update(visible(slug, request), body, true);
    }

    @DeleteMapping("/{slug}")
    public ResponseEntity<Void> delete(@PathVariable String slug, HttpServletRequest request) {
        service.delete(visible(slug, request));
        return ResponseEntity.noContent().build();
    }

    private Book visible(String slug, HttpServletRequest request) {
        return service.visible(BookQuery.of(request), slug);
    }
}
