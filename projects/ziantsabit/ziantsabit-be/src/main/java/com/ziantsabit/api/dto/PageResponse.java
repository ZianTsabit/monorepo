package com.ziantsabit.api.dto;

import java.util.List;
import java.util.TreeMap;
import java.util.function.BiFunction;

import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.ziantsabit.api.exception.ApiException;

import jakarta.servlet.http.HttpServletRequest;

/**
 * One page of a list, in the envelope DRF's PageNumberPagination used:
 * {@code {count, next, previous, results}}.
 *
 * <p>{@code next} and {@code previous} are absolute URLs built from the request
 * itself, so a client follows them as given -- {@code useAdminPosts} and
 * {@code usePosts} in the SPA do exactly that for "Load more".
 */
public record PageResponse<T>(long count, String next, String previous, List<T> results) {

    /** Request attribute holding the path before trailing-slash normalisation (see WebConfig). */
    public static final String ORIGINAL_PATH = PageResponse.class.getName() + ".originalPath";

    /**
     * Read {@code ?page=}, fetch that page, and wrap it.
     *
     * <p>A page that is not a positive integer, or is past the last page, is a
     * 404 rather than an empty page -- what DRF did, and what tells a client
     * holding a stale "next" link that the list has shrunk under it. Page 1 of
     * an empty list is still a page, with no results.
     *
     * @param fetch given (limit, offset), returns that slice in order
     */
    public static <T> PageResponse<T> of(
            HttpServletRequest request, int pageSize, long count, BiFunction<Integer, Long, List<T>> fetch) {
        long pages = Math.max(1, (count + pageSize - 1) / pageSize);
        long page = pageNumber(request.getParameter("page"), pages);
        List<T> results = fetch.apply(pageSize, (page - 1) * pageSize);
        String next = page < pages ? link(request, page + 1) : null;
        String previous = page > 1 ? link(request, page - 1) : null;
        return new PageResponse<>(count, next, previous, results);
    }

    private static long pageNumber(String raw, long pages) {
        if (raw == null || raw.isEmpty()) {
            return 1;
        }
        if (raw.equals("last")) {
            return pages;
        }
        long page;
        try {
            page = Long.parseLong(raw.strip());
        } catch (NumberFormatException notANumber) {
            throw ApiException.notFound("Invalid page.");
        }
        if (page < 1 || page > pages) {
            throw ApiException.notFound("Invalid page.");
        }
        return page;
    }

    private static String link(HttpServletRequest request, long page) {
        ServletUriComponentsBuilder url = ServletUriComponentsBuilder.fromRequest(request);
        // The path as the client sent it, trailing slash included, rather than
        // as the trailing-slash filter normalised it for routing.
        Object original = request.getAttribute(ORIGINAL_PATH);
        if (original instanceof String path) {
            url.replacePath(path);
        }
        // Every other parameter kept, in alphabetical order as DRF wrote them;
        // page 1 is the URL with no page at all.
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>(url.build().getQueryParams());
        params.remove("page");
        if (page > 1) {
            params.add("page", Long.toString(page));
        }
        url.replaceQueryParams(new LinkedMultiValueMap<>(new TreeMap<>(params)));
        // Not encode(): the query string is taken from the request already
        // encoded, and encoding it again would turn every %20 into %2520.
        return url.build().toUriString();
    }
}
