package com.ziantsabit.api.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.test.context.TestPropertySource;

import com.ziantsabit.api.support.ApiTestBase;
import com.ziantsabit.api.support.Response;

/** ADMIN_USERNAME / ADMIN_PASSWORD: the stand-in for `manage.py createsuperuser`. */
@TestPropertySource(properties = {"app.admin-username=owner", "app.admin-password=first-password"})
class AdminBootstrapTest extends ApiTestBase {

    @Autowired
    AdminBootstrap bootstrap;

    @Test
    void createsTheAccountWhenItIsMissing() {
        bootstrap.run(new DefaultApplicationArguments());
        Response response = anonymous().basicAuth("owner", "first-password").post("/api/posts/", body("title", "X"));
        assertThat(response.status()).isEqualTo(201);
    }

    @Test
    void neverResetsAnExistingAccount() {
        accounts.create("owner", "changed-since");
        bootstrap.run(new DefaultApplicationArguments());
        assertThat(anonymous().basicAuth("owner", "first-password").post("/api/posts/", body("title", "X")).status())
                .isEqualTo(403);
        assertThat(anonymous().basicAuth("owner", "changed-since").post("/api/posts/", body("title", "X")).status())
                .isEqualTo(201);
    }
}
