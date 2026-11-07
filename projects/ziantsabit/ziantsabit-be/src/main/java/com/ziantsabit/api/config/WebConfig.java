package com.ziantsabit.api.config;

import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.Locale;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.filter.UrlHandlerFilter;

import com.ziantsabit.api.dto.PageResponse;
import com.ziantsabit.api.security.Throttle;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

@Configuration
public class WebConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** Posting a comment: roughly one every three minutes, sustained, by default. */
    @Bean
    Throttle commentThrottle(AppProperties properties, Clock clock) {
        return new Throttle("comments", properties.commentRateLimit(), clock);
    }

    /** The reaction bar: generous, since a toggle invites changing your mind. */
    @Bean
    Throttle reactionThrottle(AppProperties properties, Clock clock) {
        return new Throttle("reactions", properties.reactionRateLimit(), clock);
    }

    /**
     * {@code /api/posts/} and {@code /api/posts} are the same route.
     *
     * <p>Django's URLs all ended in a slash, and so does every URL the SPA
     * builds; Spring matches neither form to the other by default. Trimming the
     * slash before anything else sees the request -- the security rules
     * included, which is why this runs first -- keeps both working, so neither
     * the SPA nor a bookmarked {@code /api/docs/} needs to change.
     */
    @Bean
    FilterRegistrationBean<UrlHandlerFilter> trailingSlashes() {
        FilterRegistrationBean<UrlHandlerFilter> registration = new FilterRegistrationBean<>(
                UrlHandlerFilter.trailingSlashHandler("/**").wrapRequest().build());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        return registration;
    }

    /** Remember the path as sent, so pagination links can echo it back unchanged. */
    @Bean
    FilterRegistrationBean<OncePerRequestFilter> originalPath() {
        FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>(
                new OncePerRequestFilter() {
                    @Override
                    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                            FilterChain chain) throws ServletException, IOException {
                        request.setAttribute(PageResponse.ORIGINAL_PATH, request.getRequestURI());
                        chain.doFilter(request, response);
                    }
                });
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    /**
     * Refuse a request addressed to a host the API does not serve, as Django's
     * ALLOWED_HOSTS did. Pagination links are built from the Host header, so a
     * forged one would otherwise be echoed back as an absolute URL.
     *
     * <p>The health check is exempt: the container probes itself on 127.0.0.1,
     * which production's list deliberately does not include.
     */
    @Bean
    FilterRegistrationBean<OncePerRequestFilter> allowedHosts(AppProperties properties, JsonMapper json) {
        List<String> allowed = properties.allowedHostList().stream()
                .map(host -> host.toLowerCase(Locale.ROOT)).toList();
        OncePerRequestFilter filter = new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                    FilterChain chain) throws ServletException, IOException {
                if (request.getRequestURI().startsWith("/actuator/health")
                        || hostAllowed(request.getServerName().toLowerCase(Locale.ROOT), allowed)) {
                    chain.doFilter(request, response);
                    return;
                }
                SecurityConfig.write(response, json, HttpStatus.BAD_REQUEST, "Bad Request (400)");
            }
        };
        FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 2);
        return registration;
    }

    /** Django's matching: exact, {@code .example.com} for the domain and its subdomains, or {@code *}. */
    static boolean hostAllowed(String host, List<String> allowed) {
        for (String pattern : allowed) {
            if (pattern.equals("*") || pattern.equals(host)) {
                return true;
            }
            if (pattern.startsWith(".") && (host.endsWith(pattern) || host.equals(pattern.substring(1)))) {
                return true;
            }
        }
        return false;
    }
}
