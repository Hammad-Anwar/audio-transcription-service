package com.example.audiotranscription.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import com.example.audiotranscription.domain.TranscriptionStatus;
import com.example.audiotranscription.error.AudioNotFoundException;
import com.example.audiotranscription.error.SegmentNotFoundException;
import com.example.audiotranscription.error.TranscriptFailedException;
import com.example.audiotranscription.error.TranscriptNotReadyException;
import com.example.audiotranscription.storage.StoredAudio;
import com.example.audiotranscription.transcription.TranscriptionResult;
import com.example.audiotranscription.transcription.TranscriptionSegmentResult;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAudioPersistenceService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
class AudioPersistenceServiceTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private JpaAudioPersistenceService persistence;

    @Autowired
    private AudioFileRepository audioFiles;

    @Autowired
    private TranscriptSegmentRepository segments;

    @Autowired
    private EntityManager entityManager;

    @Test
    void createsProcessingAndCompletesWithAllSegmentsAtomically() {
        UUID audioId = UUID.randomUUID();
        persistence.createProcessing(audioId, stored(audioId));
        TranscriptionResult result = result();

        persistence.complete(audioId, result);
        entityManager.clear();

        AudioFile audio = audioFiles.findById(audioId).orElseThrow();
        assertThat(audio.getStatus()).isEqualTo(TranscriptionStatus.COMPLETED);
        assertThat(audio.getDurationMs()).isEqualTo(10_200);
        assertThat(audio.getLanguage()).isEqualTo("en");
        assertThat(audio.getErrorMessage()).isNull();
        assertThat(segments.findByAudioFileIdOrderBySegmentIndexAsc(audioId))
                .extracting(TranscriptSegment::getSegmentIndex)
                .containsExactly(0, 1);
    }

    @Test
    void failedCompletionRollsBackSegmentsAndStatus() {
        UUID audioId = UUID.randomUUID();
        persistence.createProcessing(audioId, stored(audioId));
        TranscriptionResult duplicateIndexes = new TranscriptionResult(
                "en",
                2_000,
                List.of(
                        new TranscriptionSegmentResult(0, 0, 1_000, "First"),
                        new TranscriptionSegmentResult(0, 1_000, 2_000, "Duplicate")
                )
        );

        assertThatThrownBy(() -> persistence.complete(audioId, duplicateIndexes))
                .isInstanceOf(RuntimeException.class);
        entityManager.clear();

        assertThat(audioFiles.findById(audioId).orElseThrow().getStatus())
                .isEqualTo(TranscriptionStatus.PROCESSING);
        assertThat(segments.findByAudioFileIdOrderBySegmentIndexAsc(audioId)).isEmpty();
    }

    @Test
    void marksFailureAndBoundsStoredDiagnostic() {
        UUID audioId = UUID.randomUUID();
        persistence.createProcessing(audioId, stored(audioId));

        persistence.fail(audioId, "x".repeat(1_500));
        entityManager.clear();

        AudioFile audio = audioFiles.findById(audioId).orElseThrow();
        assertThat(audio.getStatus()).isEqualTo(TranscriptionStatus.FAILED);
        assertThat(audio.getErrorMessage()).hasSize(1_000);
        assertThatThrownBy(() -> persistence.requireTranscript(audioId))
                .isInstanceOf(TranscriptFailedException.class);
    }

    @Test
    void distinguishesMissingNotReadyAndNoSegmentStates() {
        UUID missing = UUID.randomUUID();
        assertThatThrownBy(() -> persistence.requireAudio(missing))
                .isInstanceOf(AudioNotFoundException.class);

        UUID processing = UUID.randomUUID();
        persistence.createProcessing(processing, stored(processing));
        assertThatThrownBy(() -> persistence.requireTranscript(processing))
                .isInstanceOf(TranscriptNotReadyException.class);

        persistence.complete(processing, result());
        assertThatThrownBy(() -> persistence.requireSegmentAt(processing, 10_200))
                .isInstanceOf(SegmentNotFoundException.class);
    }

    private static StoredAudio stored(UUID audioId) {
        return new StoredAudio(
                "lecture.mp3",
                audioId + ".mp3",
                Path.of(audioId + ".mp3"),
                Path.of("/tmp", audioId + ".mp3"),
                "audio/mpeg",
                3
        );
    }

    private static TranscriptionResult result() {
        return new TranscriptionResult(
                "en",
                10_200,
                List.of(
                        new TranscriptionSegmentResult(0, 0, 4_800, "Hello"),
                        new TranscriptionSegmentResult(1, 4_800, 10_200, "World")
                )
        );
    }
}
