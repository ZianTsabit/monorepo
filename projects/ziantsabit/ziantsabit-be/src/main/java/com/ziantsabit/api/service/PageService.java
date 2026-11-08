package com.ziantsabit.api.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ziantsabit.api.dto.PageRequest;
import com.ziantsabit.api.exception.ApiException;
import com.ziantsabit.api.model.PageContent;
import com.ziantsabit.api.repository.PageRepository;
import com.ziantsabit.api.util.Sql;
import com.ziantsabit.api.validation.PageDocuments;
import com.ziantsabit.api.validation.RequestBodies;

import tools.jackson.databind.JsonNode;

/** Reading and writing the CV and About page documents. */
@Service
public class PageService {

    private final PageRepository pages;
    private final RequestBodies bodies;

    public PageService(PageRepository pages, RequestBodies bodies) {
        this.pages = pages;
        this.bodies = bodies;
    }

    public List<PageContent> list() {
        return pages.findAll();
    }

    /**
     * The row for this key, created empty if it has never been saved -- for a
     * key added later, or a database restored from before the seed. The page
     * exists, so a 404 would be a lie, and a PATCH would have no row to fix.
     */
    @Transactional
    public PageContent get(String key) {
        if (!PageDocuments.KEYS.contains(key)) {
            throw ApiException.notFound("No page called '" + key + "'.");
        }
        Optional<PageContent> stored = pages.findByKey(key);
        if (stored.isPresent()) {
            return stored.get();
        }
        pages.insertIfMissing(key, PageDocuments.empty(key), Sql.now());
        return pages.findByKey(key).orElseThrow();
    }

    /** PATCH: a new {@code data} document replaces the stored one; nothing else is writable. */
    @Transactional
    public PageContent update(String key, JsonNode body) {
        PageContent page = get(key);
        PageRequest request = bodies.patch(new PageRequest(page.data()), body, PageRequest.class);
        Map<String, Object> document;
        try {
            document = PageDocuments.normalise(key, request.data());
        } catch (PageDocuments.Invalid invalid) {
            throw ApiException.invalid(Map.of("data", invalid.detail()));
        }
        Instant now = Sql.now();
        pages.update(key, document, now);
        // The document as normalised, not re-read: jsonb stores keys in its own
        // order, and the write's response keeps the order the shape declares.
        return new PageContent(key, document, now);
    }
}
