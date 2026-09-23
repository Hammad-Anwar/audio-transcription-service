package com.example.audiotranscription.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.example.audiotranscription.config.CacheConfiguration;
import com.example.audiotranscription.dto.AudioMetadataDto;
import com.example.audiotranscription.dto.TranscriptDto;
import com.example.audiotranscription.model.AudioFile;
import com.example.audiotranscription.model.TranscriptSegment;
import com.example.audiotranscription.model.TranscriptionStatus;
import com.example.audiotranscription.repository.AudioFileRepository;
import com.example.audiotranscription.repository.TranscriptSegmentRepository;
import com.example.audiotranscription.whisper.WhisperTranscriptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.support.TransactionTemplate;

@SpringJUnitConfig(AudioServiceCacheTest.TestConfiguration.class)
class AudioServiceCacheTest {

    @Autowired
    private AudioService service;

    @Autowired
    private AudioFileRepository audioFiles;

    @Autowired
    private TranscriptSegmentRepository segments;

    @Autowired
    private CacheManager cacheManager;

    @BeforeEach
    void clearCaches() {
        cacheManager.getCacheNames().forEach(name -> cacheManager.getCache(name).clear());
        org.mockito.Mockito.reset(audioFiles, segments);
    }

    @Test
    void cachesCompletedMetadataOnly() {
        UUID completedId = UUID.randomUUID();
        AudioFile completed = audio(completedId, TranscriptionStatus.PROCESSING);
        completed.markCompleted(1_000, "en");
        when(audioFiles.findById(completedId)).thenReturn(Optional.of(completed));

        AudioMetadataDto first = service.getAudio(completedId);
        AudioMetadataDto second = service.getAudio(completedId);

        assertThat(second).isEqualTo(first);
        verify(audioFiles).findById(completedId);

        assertMetadataStateIsNotCached(TranscriptionStatus.PROCESSING);
        assertMetadataStateIsNotCached(TranscriptionStatus.FAILED);
    }

    @Test
    void cachesCompletedTranscriptButNotListOrTimestampLookup() {
        UUID audioId = UUID.randomUUID();
        AudioFile completed = audio(audioId, TranscriptionStatus.PROCESSING);
        completed.markCompleted(1_000, "en");
        TranscriptSegment segment = new TranscriptSegment(completed, 0, 0, 1_000, "Hello");
        when(audioFiles.findById(audioId)).thenReturn(Optional.of(completed));
        when(segments.findByAudioFileIdOrderBySegmentIndexAsc(audioId))
                .thenReturn(List.of(segment));
        when(segments.findAtTime(audioId, 0)).thenReturn(Optional.of(segment));
        when(audioFiles.findAll()).thenReturn(List.of(completed));

        TranscriptDto first = service.getTranscript(audioId);
        TranscriptDto second = service.getTranscript(audioId);
        service.getAllAudio();
        service.getAllAudio();
        service.getSegmentAt(audioId, 0);
        service.getSegmentAt(audioId, 0);

        assertThat(second).isEqualTo(first);
        verify(segments).findByAudioFileIdOrderBySegmentIndexAsc(audioId);
        verify(audioFiles, times(3)).findById(audioId);
        verify(audioFiles, times(2)).findAll();
        verify(segments, times(2)).findAtTime(audioId, 0);
    }

    private void assertMetadataStateIsNotCached(TranscriptionStatus status) {
        UUID audioId = UUID.randomUUID();
        AudioFile audio = audio(audioId, status);
        if (status == TranscriptionStatus.FAILED) {
            audio.markFailed("failed");
        }
        when(audioFiles.findById(audioId)).thenReturn(Optional.of(audio));

        service.getAudio(audioId);
        service.getAudio(audioId);

        verify(audioFiles, times(2)).findById(audioId);
    }

    private static AudioFile audio(UUID id, TranscriptionStatus status) {
        return new AudioFile(
                id, "lecture.mp3", id + ".mp3", id + ".mp3",
                "audio/mpeg", 3, status
        );
    }

    @Configuration(proxyBeanMethods = false)
    @EnableCaching
    static class TestConfiguration {

        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager(
                    CacheConfiguration.AUDIO_METADATA_CACHE,
                    CacheConfiguration.TRANSCRIPTS_CACHE
            );
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
