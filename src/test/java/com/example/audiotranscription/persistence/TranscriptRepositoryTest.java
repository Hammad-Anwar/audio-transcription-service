package com.example.audiotranscription.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import com.example.audiotranscription.domain.TranscriptionStatus;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class TranscriptRepositoryTest {

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
    private AudioFileRepository audioFiles;

    @Autowired
    private TranscriptSegmentRepository segments;

    @Autowired
    private EntityManager entityManager;

    @Test
    void ordersSegmentsAndUsesHalfOpenTimestampRanges() {
        UUID audioId = UUID.randomUUID();
        AudioFile audio = audioFiles.saveAndFlush(processingAudio(audioId));
        segments.saveAllAndFlush(List.of(
                new TranscriptSegment(audio, 2, 10_200, 15_600, "Third"),
                new TranscriptSegment(audio, 0, 0, 4_800, "First"),
                new TranscriptSegment(audio, 1, 4_800, 10_200, "Second")
        ));

        assertThat(segments.findByAudioFileIdOrderBySegmentIndexAsc(audioId))
                .extracting(TranscriptSegment::getSegmentIndex)
                .containsExactly(0, 1, 2);
        assertThat(segments.findAtTime(audioId, 4_800))
                .get()
                .extracting(TranscriptSegment::getSegmentIndex)
                .isEqualTo(1);
        assertThat(segments.findAtTime(audioId, 15_600)).isEmpty();
    }

    @Test
    void deletingAudioCascadesToSegments() {
        UUID audioId = UUID.randomUUID();
        AudioFile audio = audioFiles.saveAndFlush(processingAudio(audioId));
        segments.saveAndFlush(new TranscriptSegment(audio, 0, 0, 1_000, "Only segment"));
        entityManager.clear();

        audioFiles.deleteById(audioId);
        audioFiles.flush();

        assertThat(segments.findByAudioFileIdOrderBySegmentIndexAsc(audioId)).isEmpty();
    }

    private static AudioFile processingAudio(UUID audioId) {
        return new AudioFile(
                audioId,
                "lecture.mp3",
                audioId + ".mp3",
                audioId + ".mp3",
                "audio/mpeg",
                3L,
                TranscriptionStatus.PROCESSING
        );
    }
}
