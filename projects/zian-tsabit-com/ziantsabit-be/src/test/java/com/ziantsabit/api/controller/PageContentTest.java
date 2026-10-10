package com.ziantsabit.api.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziantsabit.api.support.ApiTestBase;
import com.ziantsabit.api.support.Response;

import tools.jackson.core.type.TypeReference;

/** /api/pages/. Ported from the Django suite's PageContentTests. */
class PageContentTest extends ApiTestBase {

    static final String CV = "/api/pages/cv/";
    static final String ABOUT = "/api/pages/about/";

    @BeforeEach
    void fixtures() {
        createUser();
    }

    Map<String, Object> stored(String key) {
        String data = jdbc.sql("SELECT data::text FROM myapp_pagecontent WHERE key = :key").param("key", key)
                .query(String.class).single();
        return json.readValue(data, new TypeReference<Map<String, Object>>() {
        });
    }

    @SuppressWarnings("unchecked")
    static <T> T at(Object document, Object... path) {
        Object node = document;
        for (Object step : path) {
            node = step instanceof Integer index ? ((List<Object>) node).get(index) : ((Map<String, Object>) node).get(step);
        }
        return (T) node;
    }

    @Test
    void seedMigrationFilledBothPages() {
        Map<String, Object> cv = stored("cv");
        assertThat(cv.get("name")).isEqualTo("Ghazian Tsabit Alkamil");
        List<Object> entries = at(cv, "experience", "entries");
        List<Object> sections = at(stored("about"), "sections");
        assertThat(entries).isNotEmpty();
        assertThat(sections).isNotEmpty();
    }

    @Test
    void theBamtrenLinkSurvivedAsMarkdown() {
        String point = at(stored("cv"), "experience", "entries", 1, "points", 0);
        assertThat(point).contains("[Bamtren](https://bamtren.com/)");
    }

    @Test
    void theEscapedDollarSurvived() {
        String point = at(stored("cv"), "experience", "entries", 0, "points", 0);
        assertThat(point).contains("~\\$2,000");
    }

    @Test
    void anyoneMayReadAPage() {
        Response response = client.get(CV);
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.string("key")).isEqualTo("cv");
        assertThat(response.map().keySet()).containsExactly("key", "data", "updated_at");
    }

    @Test
    void listReturnsBothPagesUnpaginated() {
        Response response = client.get("/api/pages/");
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.list().stream().map(page -> page.get("key")).toList()).containsExactly("about", "cv");
    }

    @Test
    void anonymousMayNotWrite() {
        assertThat(client.patch(CV, body("data", Map.of())).status()).isEqualTo(403);
    }

    @Test
    void ownerMayWrite() {
        Response response = owner().patch(CV, body("data", Map.of("name", "New Name")));
        assertThat(response.status()).isEqualTo(200);
        assertThat(stored("cv").get("name")).isEqualTo("New Name");
    }

    @Test
    void aWriteFillsTheMissingKeys() {
        Response response = owner().patch(CV, body("data", Map.of("name", "Only a name")));
        Map<String, Object> data = at(response.map(), "data");
        List<Object> entries = at(data, "experience", "entries");
        assertThat(entries).isEmpty();
        assertThat((String) at(data, "summary", "heading")).isEqualTo("📄 Summary");
        assertThat(data.keySet()).containsExactly("name", "location", "links", "summary", "experience",
                "projects", "skills", "education");
    }

    @Test
    void unknownKeysAreDropped() {
        Response response = owner().patch(CV, body("data", Map.of("name", "N", "nonsense", 1)));
        Map<String, Object> data = at(response.map(), "data");
        assertThat(data).doesNotContainKey("nonsense");
    }

    @Test
    void aWrongTypeIsA400() {
        Response response = owner().patch(CV, body("data", Map.of("links", "not a list")));
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.get("data")).isEqualTo(Map.of("links", List.of("Expected a list of objects.")));
    }

    @Test
    void aNonObjectDocumentIsA400() {
        Response response = owner().patch(CV, body("data", List.of(1, 2)));
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.get("data")).isEqualTo(Map.of("data", List.of("Expected an object.")));
    }

    @Test
    void anEntryWithNoTitleIsDropped() {
        Response response = owner().patch(CV, body("data", Map.of("experience",
                Map.of("entries", List.of(Map.of("title", "Real"), Map.of("subtitle", "Orphan"))))));
        List<Map<String, Object>> entries = at(response.map(), "data", "experience", "entries");
        assertThat(entries.stream().map(entry -> entry.get("title")).toList()).containsExactly("Real");
    }

    @Test
    void blankPointsAreDroppedAndTheRestTrimmed() {
        Response response = owner().patch(CV, body("data", Map.of("experience",
                Map.of("entries", List.of(Map.of("title", "T", "points", List.of("  a  ", "", "   ")))))));
        List<Object> points = at(response.map(), "data", "experience", "entries", 0, "points");
        assertThat(points).containsExactly("a");
    }

    @Test
    void keyIsReadOnly() {
        owner().patch(CV, body("key", "about", "data", Map.of()));
        assertThat(count("myapp_pagecontent")).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM myapp_pagecontent WHERE key = 'cv'").query(Long.class).single())
                .isEqualTo(1);
    }

    @Test
    void putRequiresTheDocument() {
        Response response = owner().put(CV, body());
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.get("data")).isEqualTo(List.of("This field is required."));
    }

    @Test
    void thereIsNoCreateOrDelete() {
        assertThat(owner().post("/api/pages/", body("key", "extra")).status()).isEqualTo(405);
        assertThat(owner().delete(CV).status()).isEqualTo(405);
    }

    @Test
    void anUnknownKeyIsA404() {
        Response response = client.get("/api/pages/nope/");
        assertThat(response.status()).isEqualTo(404);
        assertThat(response.string("detail")).isEqualTo("No page called 'nope'.");
    }

    @Test
    void aMissingRowIsCreatedEmptyRatherThan404ing() {
        jdbc.sql("DELETE FROM myapp_pagecontent WHERE key = 'about'").update();
        Response response = client.get(ABOUT);
        assertThat(response.status()).isEqualTo(200);
        List<Object> sections = at(response.map(), "data", "sections");
        assertThat(sections).isEmpty();
        assertThat(count("myapp_pagecontent")).isEqualTo(2);
    }
}
