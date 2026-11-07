package com.ziantsabit.api.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Swaps the S3 bucket for {@link InMemoryStorage} in every API test. */
@TestConfiguration
public class TestStorageConfig {

    @Bean
    @Primary
    InMemoryStorage inMemoryStorage() {
        return new InMemoryStorage();
    }
}
