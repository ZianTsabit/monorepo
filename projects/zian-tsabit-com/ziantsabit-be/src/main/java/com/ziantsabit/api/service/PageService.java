package com.ziantsabit.api.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ziantsabit.api.exception.ApiException;
import com.ziantsabit.api.model.PageContent;
import com.ziantsabit.api.repository.PageRepository;
import com.ziantsabit.api.util.Sql;
import com.ziantsabit.api.validation.Form;
import com.ziantsabit.api.validation.PageDocuments;

/** Reading and writing the CV and About page documents. */
@Service
public class PageService {

    private final PageRepository pages;

    public PageService(PageRepository pages) {
        this.pages = pages;
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

    /**
     * PUT ({@code partial} false) or PATCH. Only {@code data} is writable: a
     * writable key would let a PUT turn the CV into the About page.
     */
    @Transactional
    public PageContent update(String key, Object body, boolean partial) {
        PageContent page = get(key);
        Form form = Form.of(body, partial);
        Optional<Map<String, Object>> data = form.json("data", true).flatMap(raw -> {
            try {
                return Optional.of(PageDocuments.normalise(key, raw));
            } catch (PageDocuments.Invalid invalid) {
                form.error("data", invalid.detail());
                return Optional.empty();
            }
        });
        form.check();
        Map<String, Object> document = data.orElse(page.data());
        Instant now = Sql.now();
        pages.update(key, document, now);
        // The document as normalised, not re-read: jsonb stores keys in its own
        // order, and the write's response keeps the order the shape declares.
        return new PageContent(key, document, now);
    }
}
