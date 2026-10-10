package com.ziantsabit.api.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ziantsabit.api.dto.ApiDocs;
import com.ziantsabit.api.model.PageContent;
import com.ziantsabit.api.service.PageService;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * The CV and About pages' content: read by anyone, written by the owner.
 *
 * <p><b>No create and no delete.</b> The set of pages is fixed at two, each
 * already has a route and a component; a third row would be content nothing
 * renders, and deleting one would leave a public page with nothing to show.
 * Addressed by key ({@code /api/pages/cv/}) so the SPA can write the URL down
 * rather than look it up, and unpaginated, since two rows never need a pager.
 */
@RestController
@RequestMapping("/api/pages")
@Tag(name = "pages")
public class PageController {

    private final PageService service;

    public PageController(PageService service) {
        this.service = service;
    }

    @GetMapping
    public List<PageContent> list() {
        return service.list();
    }

    @GetMapping("/{key}")
    public PageContent retrieve(@PathVariable String key) {
        return service.get(key);
    }

    @PutMapping("/{key}")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            content = @Content(schema = @Schema(implementation = ApiDocs.PageInput.class)))
    public PageContent replace(@PathVariable String key, @RequestBody(required = false) Object body) {
        return service.update(key, body, false);
    }

    @PatchMapping("/{key}")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            content = @Content(schema = @Schema(implementation = ApiDocs.PageInput.class)))
    public PageContent patch(@PathVariable String key, @RequestBody(required = false) Object body) {
        return service.update(key, body, true);
    }
}
