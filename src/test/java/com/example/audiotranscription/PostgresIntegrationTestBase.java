package com.example.audiotranscription;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
public abstract class PostgresIntegrationTestBase {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine");

    static final Path STORAGE_ROOT = createStorageRoot();

    @DynamicPropertySource
    static void configureIntegrationEnvironment(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.storage.audio-directory", () -> STORAGE_ROOT.toString());
    }

    private static Path createStorageRoot() {
        try {
            return Files.createTempDirectory("audio-transcription-integration-");
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
