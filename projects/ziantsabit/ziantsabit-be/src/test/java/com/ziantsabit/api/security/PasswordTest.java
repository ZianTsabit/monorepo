package com.ziantsabit.api.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.ziantsabit.api.support.Api;
import com.ziantsabit.api.support.ApiTestBase;
import com.ziantsabit.api.support.Response;

/** How passwords are stored, and how a hash from before V4 becomes bcrypt on first use. */
class PasswordTest extends ApiTestBase {

    // PBKDF2-HMAC-SHA256 of "pw-for-tests", 1000 rounds, salt "abcdefghijklmnopqrstuv":
    // the format app_user held before V4, which V4 tagged {pbkdf2-legacy}.
    static final String LEGACY_HASH =
            "pbkdf2_sha256$1000$abcdefghijklmnopqrstuv$yH2VGHMchEp1vrLNWnm89deW1DEjt3ZQbjqugpX9tLo=";

    @Autowired
    PasswordEncoder passwords;

    @BeforeEach
    void legacyAccount() {
        jdbc.sql("INSERT INTO app_user (username, password) VALUES ('legacy', :hash)")
                .param("hash", "{" + LegacyPbkdf2PasswordEncoder.ID + "}" + LEGACY_HASH).update();
    }

    String storedHash() {
        return jdbc.sql("SELECT password FROM app_user WHERE username = 'legacy'").query(String.class).single();
    }

    @Test
    void theLegacyMatcherVerifiesTheOldFormatOnly() {
        LegacyPbkdf2PasswordEncoder legacy = new LegacyPbkdf2PasswordEncoder();
        assertThat(legacy.matches("pw-for-tests", LEGACY_HASH)).isTrue();
        assertThat(legacy.matches("wrong", LEGACY_HASH)).isFalse();
        assertThat(legacy.matches("pw-for-tests", "pbkdf2_sha256$1000$salt$not-base64!")).isFalse();
        assertThat(legacy.matches("", LEGACY_HASH)).isFalse();
    }

    @Test
    void newPasswordsAreBcrypt() {
        accounts.create("fresh", "secret");
        String hash = jdbc.sql("SELECT password FROM app_user WHERE username = 'fresh'").query(String.class).single();
        assertThat(hash).startsWith("{bcrypt}$2");
        assertThat(passwords.matches("secret", hash)).isTrue();
    }

    @Test
    void anUnusableOrUnknownHashMatchesNothing() {
        assertThat(passwords.matches("pw-for-tests", "!unusable")).isFalse();
        assertThat(passwords.matches("pw-for-tests", LEGACY_HASH)).isFalse();
    }

    @Test
    void basicAuthWithALegacyHashWorksAndUpgradesIt() {
        Response response = anonymous().basicAuth("legacy", "pw-for-tests").post("/api/posts/", body("title", "Hi"));
        assertThat(response.status()).isEqualTo(201);
        assertThat(storedHash()).startsWith("{bcrypt}");
        // And the upgraded hash keeps working.
        assertThat(anonymous().basicAuth("legacy", "pw-for-tests").post("/api/posts/", body("title", "Again"))
                .status()).isEqualTo(201);
    }

    @Test
    void sessionLoginWithALegacyHashWorksAndUpgradesIt() {
        Api browser = browser();
        String token = browser.get("/api/auth/session/").string("csrf_token");
        Response login = browser.post("/api/auth/login/", body("username", "legacy", "password", "pw-for-tests"),
                Map.of("X-CSRFToken", token));
        assertThat(login.status()).isEqualTo(200);
        assertThat(storedHash()).startsWith("{bcrypt}");
    }

    @Test
    void aWrongPasswordLeavesTheLegacyHashAlone() {
        Response response = anonymous().basicAuth("legacy", "wrong").post("/api/posts/", body("title", "Hi"));
        assertThat(response.status()).isEqualTo(403);
        assertThat(storedHash()).startsWith("{pbkdf2-legacy}");
    }
}
