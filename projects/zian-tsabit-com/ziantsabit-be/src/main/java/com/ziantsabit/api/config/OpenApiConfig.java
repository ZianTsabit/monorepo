package com.ziantsabit.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.swagger.v3.core.jackson.ModelResolver;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;

/**
 * Swagger UI at {@code /api/docs/} and the document at {@code /api/schema/};
 * the ReDoc page is RedocController. The three routes drf-spectacular served.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openApi() {
        return new OpenAPI().info(new Info()
                .title("ziantsabit.com API")
                .version("1.0.0")
                .description("Content API for ziantsabit.com. Two resources, both addressed by slug and both "
                        + "labelled with free-form lists: posts, browsed by `?tag=`, and books, the owner's "
                        + "reading catalogue, browsed by `?genre=`. Both filters are case-insensitive "
                        + "containment tests, and both have a vocabulary endpoint -- /api/posts/tags/ and "
                        + "/api/books/genres/ -- since free text has no enum to read options off. A post also "
                        + "carries what visitors leave on it: comments, at /api/comments/?post=<slug>, and "
                        + "reactions, a fixed row of emoji at /api/posts/{slug}/reactions/. Both are open to "
                        + "anonymous writes and rate-limited because of it."));
    }

    /**
     * Describe models in snake_case, as the API serves them. The schema
     * generator runs on its own (Jackson 2) mapper and would otherwise list
     * {@code publishedAt} for a field every response calls {@code published_at}.
     */
    @Bean
    public ModelResolver snakeCaseModels() {
        return new ModelResolver(Json.mapper().copy()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE));
    }
}
