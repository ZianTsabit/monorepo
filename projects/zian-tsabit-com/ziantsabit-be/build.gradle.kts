plugins {
    java
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.ziantsabit"
version = "1.0.0"
description = "Content API for ziantsabit.com"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencyManagement {
    imports {
        mavenBom("software.amazon.awssdk:bom:2.55.13")
        // Matches the postgres:16-alpine image docker-compose.yml runs, so the
        // suite exercises the same server major the API is deployed against.
        mavenBom("io.zonky.test.postgres:embedded-postgres-binaries-bom:16.15.0")
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    // Sessions live in Postgres, as Django's did: a restart or a redeploy does
    // not log the owner out of the admin page.
    implementation("org.springframework.boot:spring-boot-starter-session-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.1.1")

    // Uploaded images go to an S3-compatible bucket (RustFS). The plain
    // URLConnection transport instead of the SDK's default Apache/Netty clients:
    // one upload at a time from an admin page needs nothing more.
    implementation("software.amazon.awssdk:s3") {
        exclude(group = "software.amazon.awssdk", module = "apache-client")
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
    }
    implementation("software.amazon.awssdk:url-connection-client")

    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // A real Postgres for the test suite without needing Docker: the schema
    // leans on arrays, jsonb and unnest(), none of which an in-memory database
    // would fake faithfully.
    testImplementation("io.zonky.test:embedded-postgres:2.2.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

// Only the executable jar; the plain one is never deployed and would make the
// Dockerfile's COPY glob ambiguous.
tasks.jar {
    enabled = false
}
