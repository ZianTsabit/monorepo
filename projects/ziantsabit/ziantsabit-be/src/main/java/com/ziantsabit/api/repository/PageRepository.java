package com.ziantsabit.api.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.ziantsabit.api.model.PageContent;
import com.ziantsabit.api.util.Sql;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** SQL for {@code myapp_pagecontent}, whose {@code data} column is jsonb. */
@Repository
public class PageRepository {

    private static final TypeReference<Map<String, Object>> DOCUMENT = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public PageRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Both pages, in key order. */
    public List<PageContent> findAll() {
        return jdbc.sql("SELECT * FROM myapp_pagecontent ORDER BY key").query(this::page).list();
    }

    public Optional<PageContent> findByKey(String key) {
        return jdbc.sql("SELECT * FROM myapp_pagecontent WHERE key = :key").param("key", key)
                .query(this::page).optional();
    }

    /** Create the row unless one exists already (two first reads may race). */
    public void insertIfMissing(String key, Map<String, Object> data, Instant now) {
        jdbc.sql("""
                INSERT INTO myapp_pagecontent (key, data, updated_at)
                VALUES (:key, CAST(:data AS jsonb), :now)
                ON CONFLICT (key) DO NOTHING""")
                .param("key", key)
                .param("data", json.writeValueAsString(data))
                .param("now", Sql.timestamp(now))
                .update();
    }

    public void update(String key, Map<String, Object> data, Instant now) {
        jdbc.sql("UPDATE myapp_pagecontent SET data = CAST(:data AS jsonb), updated_at = :now WHERE key = :key")
                .param("data", json.writeValueAsString(data))
                .param("now", Sql.timestamp(now))
                .param("key", key)
                .update();
    }

    private PageContent page(ResultSet row, int rowNum) throws SQLException {
        return new PageContent(row.getString("key"), json.readValue(row.getString("data"), DOCUMENT),
                Sql.instant(row, "updated_at"));
    }
}
