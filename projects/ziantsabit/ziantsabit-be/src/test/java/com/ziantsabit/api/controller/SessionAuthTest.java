package com.ziantsabit.api.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziantsabit.api.model.Post;
import com.ziantsabit.api.support.Api;
import com.ziantsabit.api.support.ApiTestBase;
import com.ziantsabit.api.support.Response;

/**
 * The cookie-and-CSRF flow behind the SPA's /admin page, through a client that
 * keeps cookies and enforces nothing for us -- CSRF is what these are about.
 * Ported from the Django suite's SessionAuthTests.
 */
class SessionAuthTest extends ApiTestBase {

    Api browser;

    @BeforeEach
    void fixtures() {
        createUser();
        browser = browser();
    }

    String startSession() {
        return browser.get("/api/auth/session/").string("csrf_token");
    }

    String logIn() {
        Response response = browser.post("/api/auth/login/", body("username", USERNAME, "password", PASSWORD),
                Map.of("X-CSRFToken", startSession()));
        assertThat(response.status()).isEqualTo(200);
        return response.string("csrf_token");
    }

    Response createPost(String token) {
        return browser.post("/api/posts/", body("title", "Written By The Admin Page"), Map.of("X-CSRFToken", token));
    }

    // --- session state ----------------------------------------------------

    @Test
    void sessionReportsAnonymousAndSetsTheCsrfCookie() {
        Response response = browser.get("/api/auth/session/");
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.get("authenticated")).isEqualTo(false);
        assertThat(response.map()).containsEntry("username", null);
        assertThat(response.headers("Set-Cookie")).anyMatch(cookie -> cookie.startsWith("csrftoken="));
    }

    @Test
    void sessionReportsTheUserAfterLogin() {
        logIn();
        Response response = browser.get("/api/auth/session/");
        assertThat(response.get("authenticated")).isEqualTo(true);
        assertThat(response.string("username")).isEqualTo(USERNAME);
    }

    // --- login ------------------------------------------------------------

    @Test
    void loginReturnsTheUsername() {
        Response response = browser.post("/api/auth/login/", body("username", USERNAME, "password", PASSWORD),
                Map.of("X-CSRFToken", startSession()));
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.get("authenticated")).isEqualTo(true);
        assertThat(response.string("username")).isEqualTo(USERNAME);
    }

    @Test
    void loginStampsLastLogin() {
        logIn();
        assertThat(jdbc.sql("SELECT last_login FROM auth_user").query(Object.class).single()).isNotNull();
    }

    @Test
    void loginRejectsABadPassword() {
        Response response = browser.post("/api/auth/login/", body("username", USERNAME, "password", "wrong"),
                Map.of("X-CSRFToken", startSession()));
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.string("detail")).isEqualTo("Incorrect username or password.");
    }

    @Test
    void loginRejectsAnUnknownUser() {
        Response response = browser.post("/api/auth/login/", body("username", "nobody", "password", PASSWORD),
                Map.of("X-CSRFToken", startSession()));
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.string("detail")).isEqualTo("Incorrect username or password.");
    }

    @Test
    void loginRequiresBothFields() {
        Response response = browser.post("/api/auth/login/", body("username", USERNAME),
                Map.of("X-CSRFToken", startSession()));
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.map()).containsKey("password");
    }

    @Test
    void loginRequiresACsrfToken() {
        Response response = browser.post("/api/auth/login/", body("username", USERNAME, "password", PASSWORD));
        assertThat(response.status()).isEqualTo(403);
        assertThat(response.string("detail")).contains("CSRF");
    }

    @Test
    void loginFromAnUntrustedOriginIsRefused() {
        Response response = browser.post("/api/auth/login/", body("username", USERNAME, "password", PASSWORD),
                Map.of("X-CSRFToken", startSession(), "Origin", "http://evil.example"));
        assertThat(response.status()).isEqualTo(403);
        assertThat(response.string("detail")).startsWith("CSRF Failed: Origin checking failed");
    }

    @Test
    void loginFromTheSpaOriginIsAccepted() {
        Response response = browser.post("/api/auth/login/", body("username", USERNAME, "password", PASSWORD),
                Map.of("X-CSRFToken", startSession(), "Origin", SPA_ORIGIN));
        assertThat(response.status()).isEqualTo(200);
    }

    // --- writing with the session -----------------------------------------

    @Test
    void writeWithASessionButNoCsrfTokenIsRejected() {
        logIn();
        Response response = browser.post("/api/posts/", body("title", "No Token"));
        assertThat(response.status()).isEqualTo(403);
        assertThat(response.string("detail")).isEqualTo("CSRF Failed: CSRF token missing.");
        assertThat(count("myapp_post")).isZero();
    }

    @Test
    void writeSucceedsWithTheTokenLoginReturned() {
        assertThat(createPost(logIn()).status()).isEqualTo(201);
    }

    @Test
    void theTokenFromBeforeLoginNoLongerWorks() {
        String stale = startSession();
        logIn();
        Response response = createPost(stale);
        assertThat(response.status()).isEqualTo(403);
        assertThat(response.string("detail")).contains("CSRF");
    }

    @Test
    void sessionWriteCanSeeAndPublishADraft() {
        String token = logIn();
        Post draft = post("Hidden");
        String detail = "/api/posts/" + draft.slug() + "/";

        assertThat(browser.get(detail).status()).isEqualTo(200);
        Response response = browser.patch(detail, body("status", "published"), Map.of("X-CSRFToken", token));
        assertThat(response.status()).isEqualTo(200);
        assertThat(row("myapp_post", draft.id()).get("published_at")).isNotNull();
    }

    @Test
    void anAnonymousVisitorNeedsNoTokenToComment() {
        // DRF only checked CSRF on session-authenticated requests; the public
        // pages send none, so a visitor's comment must not need one.
        Post post = published("Open");
        Response response = anonymous().post("/api/comments/",
                body("post", post.slug(), "author_name", "Ada", "body", "Hi."));
        assertThat(response.status()).isEqualTo(201);
    }

    // --- logout -----------------------------------------------------------

    @Test
    void logoutEndsTheSession() {
        String token = logIn();
        Response response = browser.post("/api/auth/logout/", null, Map.of("X-CSRFToken", token));
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.get("authenticated")).isEqualTo(false);
        assertThat(browser.get("/api/auth/session/").get("authenticated")).isEqualTo(false);
    }

    @Test
    void logoutRequiresAuthentication() {
        Response response = browser.post("/api/auth/logout/", null, Map.of("X-CSRFToken", startSession()));
        assertThat(response.status()).isEqualTo(403);
    }

    @Test
    void theSessionSurvivesInTheDatabase() {
        logIn();
        assertThat(count("spring_session")).isEqualTo(1);
    }
}
