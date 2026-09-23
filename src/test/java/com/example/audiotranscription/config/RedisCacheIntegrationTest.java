package com.example.audiotranscription.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.example.audiotranscription.PostgresIntegrationTestBase;
import com.example.audiotranscription.dto.AudioMetadataDto;
import com.example.audiotranscription.dto.TranscriptDto;
import com.example.audiotranscription.dto.TranscriptSegmentDto;
import com.example.audiotranscription.model.TranscriptionStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest
class RedisCacheIntegrationTest extends PostgresIntegrationTestBase {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7-alpine")
    ).withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.cache.type", () -> "redis");
        registry.add("spring.cache.redis.time-to-live", () -> "30m");
    }

    @Autowired
    private CacheManager cacheManager;

    @Test
    void roundTripsTypedDtosInIndependentNamespaces() {
        UUID audioId = UUID.randomUUID();
        AudioMetadataDto metadata = new AudioMetadataDto(
                audioId,
                "lecture.mp3",
                "audio/mpeg",
                3,
                1_000L,
                "en",
                TranscriptionStatus.COMPLETED,
                null,
                Instant.parse("2026-09-23T00:00:00Z"),
                Instant.parse("2026-09-23T00:00:01Z")
        );
        TranscriptDto transcript = new TranscriptDto(
                audioId,
                "lecture.mp3",
                1_000L,
                "en",
                List.of(new TranscriptSegmentDto(0, 0, 1_000, "Hello"))
        );

        cacheManager.getCache(CacheConfiguration.AUDIO_METADATA_CACHE).put(audioId, metadata);
        cacheManager.getCache(CacheConfiguration.TRANSCRIPTS_CACHE).put(audioId, transcript);

        Object cachedMetadata = cacheManager
                .getCache(CacheConfiguration.AUDIO_METADATA_CACHE)
                .get(audioId)
                .get();
        Object cachedTranscript = cacheManager
                .getCache(CacheConfiguration.TRANSCRIPTS_CACHE)
                .get(audioId)
                .get();
        assertThat(cachedMetadata).isInstanceOf(AudioMetadataDto.class).isEqualTo(metadata);
        assertThat(cachedTranscript).isInstanceOf(TranscriptDto.class).isEqualTo(transcript);
    }
}
