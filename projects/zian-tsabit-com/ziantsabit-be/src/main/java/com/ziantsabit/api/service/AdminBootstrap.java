package com.ziantsabit.api.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import com.ziantsabit.api.config.AppProperties;
import com.ziantsabit.api.security.UserAccounts;

/**
 * Creates the owner's account from {@code ADMIN_USERNAME} / {@code ADMIN_PASSWORD}
 * on startup -- the stand-in for {@code manage.py createsuperuser}, since a fresh
 * database has no user in it and nothing can be written without one.
 *
 * <p>Only ever creates. An account that already exists is left exactly as it is,
 * so leaving the variables set cannot quietly reset a password changed since,
 * and an account carried over from Django is never touched.
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final AppProperties properties;
    private final UserAccounts accounts;

    public AdminBootstrap(AppProperties properties, UserAccounts accounts) {
        this.properties = properties;
        this.accounts = accounts;
    }

    @Override
    public void run(ApplicationArguments args) {
        String username = properties.adminUsername() == null ? "" : properties.adminUsername().strip();
        if (username.isEmpty()) {
            return;
        }
        if (accounts.exists(username)) {
            log.info("Account '{}' already exists; ADMIN_USERNAME/ADMIN_PASSWORD left it unchanged", username);
            return;
        }
        String password = properties.adminPassword() == null ? "" : properties.adminPassword();
        if (password.isEmpty()) {
            log.warn("ADMIN_USERNAME is set but ADMIN_PASSWORD is empty; no account was created");
            return;
        }
        accounts.create(username, password);
        log.info("Created account '{}' from ADMIN_USERNAME", username);
    }
}
