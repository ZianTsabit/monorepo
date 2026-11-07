package com.ziantsabit.api.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ziantsabit.api.dto.ApiDocs;
import com.ziantsabit.api.dto.SessionState;
import com.ziantsabit.api.exception.ApiException;
import com.ziantsabit.api.security.UserAccounts;
import com.ziantsabit.api.security.Viewer;
import com.ziantsabit.api.validation.Form;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Session login for the SPA's /admin page.
 *
 * <p>The SPA is a cross-origin client, so these hand it the session cookie plus
 * the CSRF token every session-authenticated write has to echo back in
 * {@code X-CSRFToken}. The token travels in the response <em>body</em>: script
 * on the SPA's origin cannot read a cookie the API's origin set, so the cookie
 * is only ever useful to the browser, never to the code building the header.
 */
@RestController
@RequestMapping("/api/auth")
@Tag(name = "auth")
public class AuthController {

    private final AuthenticationManager authentication;
    private final SecurityContextRepository contexts;
    private final CsrfTokenRepository csrfTokens;
    private final UserAccounts accounts;

    public AuthController(AuthenticationManager authentication, SecurityContextRepository contexts,
            CsrfTokenRepository csrfTokens, UserAccounts accounts) {
        this.authentication = authentication;
        this.contexts = contexts;
        this.csrfTokens = csrfTokens;
        this.accounts = accounts;
    }

    @GetMapping("/session")
    @Operation(summary = "Current session, and the CSRF token to send as X-CSRFToken. The SPA calls this on "
            + "load: it answers 'am I still logged in?' and primes the csrftoken cookie in one round trip.")
    public SessionState session(HttpServletRequest request) {
        // Loads the token from its cookie, or makes one and sets the cookie.
        CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        return new SessionState(Viewer.isAuthenticated(), Viewer.username(), token.getToken());
    }

    /**
     * Start a session from a username and password. CSRF-checked even though
     * the caller is anonymous (see SecurityConfig): without that, a cross-site
     * POST could log a visitor into an account of the attacker's choosing.
     */
    @PostMapping("/login")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            content = @Content(schema = @Schema(implementation = ApiDocs.LoginInput.class)))
    public SessionState login(@RequestBody(required = false) Object body, HttpServletRequest request,
            HttpServletResponse response) {
        Form form = Form.of(body, false);
        String username = form.text("username", true, false, 0).orElse(null);
        String password = form.text("password", true, false, 0).orElse(null);
        form.check();

        Authentication user;
        try {
            user = authentication.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(username, password));
        } catch (AuthenticationException refused) {
            // Deliberately does not say which of the two was wrong.
            throw ApiException.detail(HttpStatus.BAD_REQUEST, "Incorrect username or password.");
        }

        // A new session id for the new identity, against session fixation.
        if (request.getSession(false) != null) {
            request.changeSessionId();
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(user);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        accounts.recordLogin(user.getName());

        // The token is rotated with the identity, which is why the response
        // carries a fresh one: a client that kept the token it logged in with
        // would be refused on its very first write.
        return new SessionState(true, user.getName(), rotateCsrfToken(request, response));
    }

    @PostMapping("/logout")
    @Operation(summary = "End the session.")
    public SessionState logout(HttpServletRequest request, HttpServletResponse response) {
        new SecurityContextLogoutHandler().logout(request, response,
                SecurityContextHolder.getContext().getAuthentication());
        return new SessionState(false, null, rotateCsrfToken(request, response));
    }

    private String rotateCsrfToken(HttpServletRequest request, HttpServletResponse response) {
        CsrfToken fresh = csrfTokens.generateToken(request);
        csrfTokens.saveToken(fresh, request, response);
        return fresh.getToken();
    }
}
