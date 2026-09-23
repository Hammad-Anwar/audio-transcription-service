package com.example.audiotranscription.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import com.example.audiotranscription.dto.AudioMetadataDto;
import com.example.audiotranscription.model.AudioFile;
import com.example.audiotranscription.model.TranscriptionStatus;
import com.example.audiotranscription.repository.AudioFileRepository;
import com.example.audiotranscription.repository.TranscriptSegmentRepository;
import com.example.audiotranscription.service.AudioService;
import com.example.audiotranscription.service.AudioStorageService;
import com.example.audiotranscription.whisper.WhisperTranscriptionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.support.TransactionTemplate;

@SpringJUnitConfig(CacheConfigurationTest.TestConfiguration.class)
class CacheConfigurationTest {

    @Autowired
    private AudioService service;

    @Autowired
    private AudioFileRepository audioFiles;

    @Autowired
    private Cache cache;

    @Autowired
    private CacheConfiguration cacheConfiguration;

    @Test
    void failedCacheReadFallsBackToRepository() {
        UUID audioId = UUID.randomUUID();
        AudioFile audio = new AudioFile(
                audioId, "lecture.mp3", audioId + ".mp3", audioId + ".mp3",
                "audio/mpeg", 3, TranscriptionStatus.PROCESSING
        );
        audio.markCompleted(1_000, "en");
        when(audioFiles.findById(audioId)).thenReturn(Optional.of(audio));

        AudioMetadataDto result = service.getAudio(audioId);

        assertThat(result.id()).isEqualTo(audioId);
        verify(audioFiles).findById(audioId);
    }

    @Test
    void ignoresEveryCacheOperationFailure() {
        RuntimeException failure = new DataAccessResourceFailureException("redis unavailable");

        assertThatCode(() -> {
            var errorHandler = cacheConfiguration.errorHandler();
            errorHandler.handleCacheGetError(failure, cache, "id");
            errorHandler.handleCachePutError(failure, cache, "id", "value");
            errorHandler.handleCacheEvictError(failure, cache, "id");
            errorHandler.handleCacheClearError(failure, cache);
        }).doesNotThrowAnyException();
    }

    @Configuration(proxyBeanMethods = false)
    @Import(CacheConfiguration.class)
    static class TestConfiguration {

        @Bean
        Cache cache() {
            Cache cache = mock(Cache.class);
            when(cache.getName()).thenReturn(CacheConfiguration.AUDIO_METADATA_CACHE);
            when(cache.get(any())).thenThrow(new RedisConnectionFailureException("unavailable"));
            return cache;
        }

        @Bean
        CacheManager cacheManager(Cache cache) {
            CacheManager manager = mock(CacheManager.class);
            when(manager.getCache(CacheConfiguration.AUDIO_METADATA_CACHE)).thenReturn(cache);
            return manager;
        }

        @Bean
        AudioFileRepository audioFiles() {
            return mock(AudioFileRepository.class);
        }

        @Bean
        TranscriptSegmentRepository segments() {
            return mock(TranscriptSegmentRepository.class);
        }

        @Bean
        AudioService audioService(
                AudioFileRepository audioFiles,
                TranscriptSegmentRepository segments
        ) {
            return new AudioService(
                    audioFiles,
                    segments,
                    mock(AudioStorageService.class),
                    mock(WhisperTranscriptionService.class),
                    mock(TransactionTemplate.class)
            );
        }
    }
}
