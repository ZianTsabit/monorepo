package com.ziantsabit.api.repository;

import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.ziantsabit.api.model.UserAccount;
import com.ziantsabit.api.util.Sql;

/** SQL for Django's {@code auth_user}, kept under its Django name. */
@Repository
public class UserRepository {

    private final JdbcClient jdbc;

    public UserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** By exact username, as Django's ModelBackend looked one up. */
    public Optional<UserAccount> findByUsername(String username) {
        return jdbc.sql("SELECT username, password, is_active FROM auth_user WHERE username = :username")
                .param("username", username)
                .query((row, n) -> new UserAccount(row.getString("username"), row.getString("password"),
                        row.getBoolean("is_active")))
                .optional();
    }

    public boolean exists(String username) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM auth_user WHERE username = :username)")
                .param("username", username).query(Boolean.class).single();
    }

    /**
     * A new active account, as {@code manage.py createsuperuser} made one. The
     * table has no column defaults on a Django-built database, so every column
     * is named.
     */
    public void insert(String username, String passwordHash) {
        jdbc.sql("""
                INSERT INTO auth_user (password, last_login, is_superuser, username, first_name, last_name,
                                       email, is_staff, is_active, date_joined)
                VALUES (:password, NULL, true, :username, '', '', '', true, true, :now)""")
                .param("password", passwordHash)
                .param("username", username)
                .param("now", Sql.timestamp(Sql.now()))
                .update();
    }

    /** Stamp last_login, as Django's login() did. */
    public void touchLastLogin(String username) {
        jdbc.sql("UPDATE auth_user SET last_login = :now WHERE username = :username")
                .param("now", Sql.timestamp(Sql.now())).param("username", username).update();
    }
}
