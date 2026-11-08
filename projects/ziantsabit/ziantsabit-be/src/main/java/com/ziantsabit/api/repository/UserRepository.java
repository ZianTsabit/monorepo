package com.ziantsabit.api.repository;

import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.ziantsabit.api.model.UserAccount;

/** SQL for {@code app_user}. */
@Repository
public class UserRepository {

    private final JdbcClient jdbc;

    public UserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UserAccount> findByUsername(String username) {
        return jdbc.sql("SELECT username, password, enabled FROM app_user WHERE username = :username")
                .param("username", username)
                .query((row, n) -> new UserAccount(row.getString("username"), row.getString("password"),
                        row.getBoolean("enabled")))
                .optional();
    }

    public boolean exists(String username) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM app_user WHERE username = :username)")
                .param("username", username).query(Boolean.class).single();
    }

    public void insert(String username, String passwordHash) {
        jdbc.sql("INSERT INTO app_user (username, password) VALUES (:username, :password)")
                .param("username", username).param("password", passwordHash).update();
    }

    public void updatePassword(String username, String passwordHash) {
        jdbc.sql("UPDATE app_user SET password = :password WHERE username = :username")
                .param("password", passwordHash).param("username", username).update();
    }

    public void touchLastLogin(String username) {
        jdbc.sql("UPDATE app_user SET last_login = now() WHERE username = :username")
                .param("username", username).update();
    }
}
