package com.ziantsabit.api.security;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * The half of Django's CSRF check that a token cannot do: an {@code Origin}
 * that is neither this API nor one of {@code CSRF_TRUSTED_ORIGINS} is refused
 * before the token is even looked at. Applied to the same requests the token
 * check is -- session-authenticated writes, and login.
 *
 * <p>It matters because the token is double-submit: anything that can plant a
 * cookie on the API's domain (a sibling subdomain, say) could plant a matching
 * pair. It cannot make a browser lie about where a request came from.
 */
public class CsrfOriginCheck extends OncePerRequestFilter {

    private final RequestMatcher applies;
    private final List<String> trusted;
    private final AccessDeniedHandler deny;

    public CsrfOriginCheck(RequestMatcher applies, List<String> trusted, AccessDeniedHandler deny) {
        this.applies = applies;
        this.trusted = trusted.stream().map(origin -> origin.toLowerCase(Locale.ROOT)).toList();
        this.deny = deny;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String origin = request.getHeader("Origin");
        if (origin != null && applies.matches(request) && !allowed(origin, request)) {
            AccessDeniedException refused = new CsrfException(
                    "Origin checking failed - " + origin + " does not match any trusted origins.");
            deny.handle(request, response, refused);
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean allowed(String origin, HttpServletRequest request) {
        String normalised = origin.toLowerCase(Locale.ROOT);
        return normalised.equals(own(request)) || trusted.contains(normalised);
    }

    /** This API's own origin, as the request addressed it. */
    private static String own(HttpServletRequest request) {
        String scheme = request.getScheme();
        int port = request.getServerPort();
        boolean defaultPort = ("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443);
        return (scheme + "://" + request.getServerName() + (defaultPort ? "" : ":" + port)).toLowerCase(Locale.ROOT);
    }
}
