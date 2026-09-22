package com.example.audiotranscription.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import com.example.audiotranscription.domain.TranscriptionStatus;
import com.example.audiotranscription.error.TranscriptionException;
import com.example.audiotranscription.persistence.AudioPersistenceService;
import com.example.audiotranscription.persistence.AudioFile;
import com.example.audiotranscription.persistence.TranscriptSegment;
import com.example.audiotranscription.storage.AudioStorageService;
import com.example.audiotranscription.storage.StoredAudio;
import com.example.audiotranscription.transcription.TranscriptionResult;
import com.example.audiotranscription.transcription.TranscriptionSegmentResult;
import com.example.audiotranscription.transcription.TranscriptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockMultipartFile;

class AudioServiceTest {

    private final AudioStorageService storage = mock(AudioStorageService.class);
    private final TranscriptionService transcription = mock(TranscriptionService.class);
    private final AudioPersistenceService persistence = mock(AudioPersistenceService.class);

    private AudioService service;
    private MockMultipartFile file;
    private StoredAudio stored;

    @BeforeEach
    void setUp() {
        service = new AudioService(storage, transcription, persistence);
        file = new MockMultipartFile(
                "file", "lecture.mp3", "audio/mpeg", new byte[] {'I', 'D', '3'}
        );
        stored = new StoredAudio(
                "lecture.mp3",
                "generated.mp3",
                Path.of("generated.mp3"),
                Path.of("/tmp/generated.mp3"),
                "audio/mpeg",
                3
        );
    }

    @Test
    void storesPersistsTranscribesAndCompletesInOrder() {
        TranscriptionResult result = new TranscriptionResult(
                "en",
                10_200,
                List.of(
                        new TranscriptionSegmentResult(0, 0, 4_800, "Hello"),
                        new TranscriptionSegmentResult(1, 4_800, 10_200, "World")
                )
        );
        when(storage.store(any(UUID.class), eq(file))).thenReturn(stored);
        when(transcription.transcribe(any())).thenReturn(result);

        AudioSummary summary = service.upload(file);

        assertThat(summary.filename()).isEqualTo("lecture.mp3");
        assertThat(summary.status()).isEqualTo(TranscriptionStatus.COMPLETED);
        assertThat(summary.durationMs()).isEqualTo(10_200);
        assertThat(summary.language()).isEqualTo("en");
        assertThat(summary.segmentCount()).isEqualTo(2);
        InOrder order = inOrder(storage, persistence, transcription);
        order.verify(storage).store(eq(summary.id()), eq(file));
        order.verify(persistence).createProcessing(summary.id(), stored);
        order.verify(transcription).transcribe(any());
        order.verify(persistence).complete(summary.id(), result);
    }

    @Test
    void deletesNewFileWhenInitialDatabaseInsertFails() {
        DataAccessResourceFailureException databaseFailure =
                new DataAccessResourceFailureException("database unavailable");
        when(storage.store(any(UUID.class), eq(file))).thenReturn(stored);
        doThrow(databaseFailure)
                .when(persistence).createProcessing(any(UUID.class), eq(stored));

        assertThatThrownBy(() -> service.upload(file)).isSameAs(databaseFailure);

        verify(storage).delete(stored);
        verifyNoInteractions(transcription);
    }

    @Test
    void preservesStoredFileAndMarksRecordFailedWhenTranscriptionFails() {
        TranscriptionException whisperFailure =
                new TranscriptionException("The local Whisper server could not process the audio.");
        when(storage.store(any(UUID.class), eq(file))).thenReturn(stored);
        when(transcription.transcribe(any())).thenThrow(whisperFailure);

        assertThatThrownBy(() -> service.upload(file))
                .isInstanceOfSatisfying(TranscriptionException.class, failure -> {
                    assertThat(failure.getAudioId()).isNotNull();
                    assertThat(failure.getMessage()).isEqualTo(whisperFailure.getMessage());
                });

        ArgumentCaptor<UUID> audioId = ArgumentCaptor.forClass(UUID.class);
        verify(persistence).fail(audioId.capture(), eq(whisperFailure.getMessage()));
        verify(storage, never()).delete(stored);
        verify(persistence, never()).complete(any(), any());
    }

    @Test
    void mapsAudioMetadataWithoutExposingStoragePaths() {
        UUID audioId = UUID.randomUUID();
        AudioFile audio = processingAudio(audioId);
        when(persistence.requireAudio(audioId)).thenReturn(audio);

        AudioDetails details = service.getAudio(audioId);

        assertThat(details.id()).isEqualTo(audioId);
        assertThat(details.filename()).isEqualTo("lecture.mp3");
        assertThat(details.contentType()).isEqualTo("audio/mpeg");
        assertThat(details.fileSize()).isEqualTo(3);
        assertThat(details.status()).isEqualTo(TranscriptionStatus.PROCESSING);
    }

    @Test
    void mapsOrderedTranscriptAndTimestampSegmentViews() {
        UUID audioId = UUID.randomUUID();
        AudioFile audio = processingAudio(audioId);
        List<TranscriptSegment> storedSegments = List.of(
                new TranscriptSegment(audio, 0, 0, 4_800, "Hello"),
                new TranscriptSegment(audio, 1, 4_800, 10_200, "World")
        );
        when(persistence.requireAudio(audioId)).thenReturn(audio);
        when(persistence.requireTranscript(audioId)).thenReturn(storedSegments);
        when(persistence.requireSegmentAt(audioId, 4_800)).thenReturn(storedSegments.get(1));

        TranscriptView transcriptView = service.getTranscript(audioId);
        SegmentView segmentView = service.getSegmentAt(audioId, 4_800);

        assertThat(transcriptView.audioId()).isEqualTo(audioId);
        assertThat(transcriptView.filename()).isEqualTo("lecture.mp3");
        assertThat(transcriptView.segments()).containsExactly(
                new SegmentView(0, 0, 4_800, "Hello"),
                new SegmentView(1, 4_800, 10_200, "World")
        );
        assertThat(segmentView).isEqualTo(new SegmentView(1, 4_800, 10_200, "World"));
    }

    private static AudioFile processingAudio(UUID audioId) {
        return new AudioFile(
                audioId,
                "lecture.mp3",
                audioId + ".mp3",
                audioId + ".mp3",
                "audio/mpeg",
                3,
                TranscriptionStatus.PROCESSING
        );
    }
}
