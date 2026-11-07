package com.ziantsabit.api.config;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.MissingCsrfTokenException;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import com.ziantsabit.api.security.CsrfOriginCheck;
import com.ziantsabit.api.security.DjangoPasswordEncoder;
import com.ziantsabit.api.security.UserAccounts;
import com.ziantsabit.api.security.Viewer;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/**
 * Who may do what, and how they prove who they are.
 *
 * <p>Reads are open. Writes need the owner -- by session cookie (the SPA's admin
 * page) or HTTP basic auth ({@code curl -u}) -- except for the three things a
 * visitor may do: leave a comment, react to a post, and count a read.
 *
 * <p>An anonymous caller refused a write gets a <b>403</b>, not a 401, and no
 * {@code WWW-Authenticate} header: a 401 challenge would make a browser pop up
 * its own login dialog and then cache whatever was typed into it.
 */
@Configuration
public class SecurityConfig {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    @Bean
    SecurityFilterChain api(HttpSecurity http, AppProperties properties, AuthenticationManager authentication,
            SecurityContextRepository contexts, CsrfTokenRepository csrfTokens, JsonMapper json) throws Exception {
        RequestMatcher csrfChecked = SecurityConfig::needsCsrfCheck;
        AuthenticationEntryPoint refuse = (request, response, failure) -> write(response, json, HttpStatus.FORBIDDEN,
                refusal(failure));
        AccessDeniedHandler deny = (request, response, denied) -> write(response, json, HttpStatus.FORBIDDEN,
                denied instanceof CsrfException csrf ? csrfFailure(csrf, request)
                        : "You do not have permission to perform this action.");

        http
                .authenticationManager(authentication)
                .cors(cors -> cors.configurationSource(cors(properties)))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokens)
                        // The token is sent and compared as-is. Spring's default
                        // masks it per response, which a token handed over in a
                        // JSON body and replayed later gains nothing from.
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        .requireCsrfProtectionMatcher(csrfChecked))
                .addFilterBefore(new CsrfOriginCheck(csrfChecked, properties.csrfTrustedOriginList(), deny),
                        CsrfFilter.class)
                .securityContext(context -> context.securityContextRepository(contexts))
                .httpBasic(basic -> basic.authenticationEntryPoint(refuse))
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .exceptionHandling(errors -> errors.authenticationEntryPoint(refuse).accessDeniedHandler(deny))
                .authorizeHttpRequests(rules -> rules
                        // The draft count and every draft's view count are the
                        // owner's business, so this read is not open.
                        .requestMatchers(HttpMethod.GET, "/api/posts/stats").authenticated()
                        .requestMatchers(HttpMethod.GET, "/**").permitAll()
                        .requestMatchers(HttpMethod.HEAD, "/**").permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                        // What a visitor may write. The comment and reaction
                        // routes are rate-limited for exactly that reason.
                        .requestMatchers(HttpMethod.POST, "/api/comments").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/posts/*/view").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/posts/*/reactions").permitAll()
                        .requestMatchers("/api/auth/logout", "/api/posts/**", "/api/books/**", "/api/comments/**",
                                "/api/pages/**", "/api/uploads/**").authenticated()
                        .anyRequest().permitAll());
        return http.build();
    }

    /**
     * Which requests must carry a CSRF token -- DRF's rule, kept exactly.
     *
     * <p>A write is checked when it is <b>authenticated by the session
     * cookie</b>, since that cookie is what a cross-site page could ride on.
     * Anonymous writes are not checked: a visitor's comment or reaction carries
     * no credential worth forging, and the public pages send no token. Basic
     * auth is not checked either -- a browser never attaches it on its own,
     * because nothing here ever answers with a challenge for it to remember.
     *
     * <p>Login is the exception, checked though the caller is anonymous:
     * otherwise a cross-site POST could sign a visitor into an account of the
     * attacker's choosing.
     *
     * <p>This runs before the basic-auth filter, so any authentication visible
     * here came from the session.
     */
    private static boolean needsCsrfCheck(HttpServletRequest request) {
        if (SAFE_METHODS.contains(request.getMethod())) {
            return false;
        }
        return request.getRequestURI().equals("/api/auth/login") || Viewer.isAuthenticated();
    }

    @Bean
    PasswordEncoder passwordEncoder(AppProperties properties) {
        return new DjangoPasswordEncoder(properties.passwordIterations());
    }

    @Bean
    AuthenticationManager authenticationManager(UserAccounts accounts, PasswordEncoder passwords) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(accounts);
        provider.setPasswordEncoder(passwords);
        return new ProviderManager(provider);
    }

    /** The session first, so a login is remembered; the request attribute for this request's own use. */
    @Bean
    SecurityContextRepository securityContextRepository() {
        return new DelegatingSecurityContextRepository(
                new RequestAttributeSecurityContextRepository(), new HttpSessionSecurityContextRepository());
    }

    /**
     * The double-submit token, in a cookie named as Django named it. The SPA
     * reads the value from {@code /api/auth/session/}'s body and sends it back
     * as {@code X-CSRFToken}; the browser sends the cookie; they must match.
     */
    @Bean
    CsrfTokenRepository csrfTokenRepository(AppProperties properties) {
        CookieCsrfTokenRepository tokens = new CookieCsrfTokenRepository();
        tokens.setCookieName("csrftoken");
        tokens.setHeaderName("X-CSRFToken");
        tokens.setParameterName("csrfmiddlewaretoken");
        tokens.setCookieCustomizer(cookie -> cookie
                .path("/")
                .maxAge(Duration.ofDays(364))
                .sameSite(properties.csrfCookieSameSite())
                .secure(properties.csrfCookieSecure()));
        return tokens;
    }

    /**
     * Every response readable by the SPA's origin, credentials included -- the
     * admin page's session cookie is dropped by the browser otherwise, and every
     * write is a 403 despite a successful login.
     */
    private static CorsConfigurationSource cors(AppProperties properties) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(properties.corsAllowedOriginList());
        config.setAllowCredentials(true);
        config.setAllowedMethods(List.of("DELETE", "GET", "OPTIONS", "PATCH", "POST", "PUT"));
        config.setAllowedHeaders(List.of(
                "accept", "authorization", "content-type", "user-agent", "x-csrftoken", "x-requested-with"));
        config.setMaxAge(Duration.ofDays(1));
        // An origin that is not listed gets no CORS headers -- and nothing else:
        // the request is served, and the browser is what refuses to hand the
        // response over. Spring's own handling would answer it with a bare 403
        // instead, which is not what django-cors-headers did, and which would
        // pre-empt the CSRF origin check's clearer refusal.
        return request -> config.checkOrigin(request.getHeader("Origin")) == null ? null : config;
    }

    private static String refusal(AuthenticationException failure) {
        if (failure instanceof BadCredentialsException) {
            return "Invalid username/password.";
        }
        if (failure instanceof DisabledException) {
            return "User inactive or deleted.";
        }
        return "Authentication credentials were not provided.";
    }

    /**
     * The words matter as well as the status: the SPA retries a write once with
     * a fresh token when a 403's detail mentions CSRF, which is how it recovers
     * from a token rotated by a login in another tab.
     */
    private static String csrfFailure(CsrfException failure, HttpServletRequest request) {
        if (failure.getMessage() != null && failure.getMessage().startsWith("Origin checking failed")) {
            return "CSRF Failed: " + failure.getMessage();
        }
        if (request.getHeader("X-CSRFToken") == null) {
            return "CSRF Failed: CSRF token missing.";
        }
        if (failure instanceof MissingCsrfTokenException) {
            return "CSRF Failed: CSRF cookie not set.";
        }
        return "CSRF Failed: CSRF token from the 'X-Csrftoken' HTTP header incorrect.";
    }

    static void write(HttpServletResponse response, JsonMapper json, HttpStatus status, String detail)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(json.writeValueAsString(Map.of("detail", detail)));
    }
}
