package com.example.audiotranscription.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import com.example.audiotranscription.dto.AudioMetadataDto;
import com.example.audiotranscription.dto.AudioUploadDto;
import com.example.audiotranscription.dto.TranscriptDto;
import com.example.audiotranscription.dto.TranscriptSegmentDto;
import com.example.audiotranscription.error.TranscriptionException;
import com.example.audiotranscription.model.AudioFile;
import com.example.audiotranscription.model.TranscriptSegment;
import com.example.audiotranscription.model.TranscriptionStatus;
import com.example.audiotranscription.repository.AudioFileRepository;
import com.example.audiotranscription.repository.TranscriptSegmentRepository;
import com.example.audiotranscription.storage.StoredAudio;
import com.example.audiotranscription.whisper.WhisperResult;
import com.example.audiotranscription.whisper.WhisperSegment;
import com.example.audiotranscription.whisper.WhisperTranscriptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class AudioServiceTest {

    private final AudioFileRepository audioFiles = org.mockito.Mockito.mock(AudioFileRepository.class);
    private final TranscriptSegmentRepository segments = org.mockito.Mockito.mock(TranscriptSegmentRepository.class);
    private final AudioStorageService storage = org.mockito.Mockito.mock(AudioStorageService.class);
    private final WhisperTranscriptionService whisper = org.mockito.Mockito.mock(WhisperTranscriptionService.class);
    private final TransactionTemplate transactions = org.mockito.Mockito.mock(TransactionTemplate.class);

    private AudioService service;
    private MockMultipartFile file;
    private StoredAudio stored;

    @BeforeEach
    void setUp() {
        doAnswer(invocation -> {
            Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(org.mockito.Mockito.mock(TransactionStatus.class));
            return null;
        }).when(transactions).executeWithoutResult(any());
        service = new AudioService(audioFiles, segments, storage, whisper, transactions);
        file = new MockMultipartFile(
                "file", "lecture.mp3", "audio/mpeg", new byte[] {'I', 'D', '3'}
        );
        stored = new StoredAudio(
                "lecture.mp3", "generated.mp3", Path.of("generated.mp3"),
                Path.of("/tmp/generated.mp3"), "audio/mpeg", 3
        );
    }

    @Test
    void storesTranscribesAndCompletesUsingShortWriteTransactions() {
        AtomicReference<AudioFile> persisted = persistSavedAudio();
        WhisperResult result = whisperResult();
        when(storage.store(any(UUID.class), eq(file))).thenReturn(stored);
        when(whisper.transcribe(any())).thenReturn(result);

        AudioUploadDto uploaded = service.upload(file);

        assertThat(uploaded.filename()).isEqualTo("lecture.mp3");
        assertThat(uploaded.status()).isEqualTo(TranscriptionStatus.COMPLETED);
        assertThat(uploaded.durationMs()).isEqualTo(10_200);
        assertThat(uploaded.language()).isEqualTo("en");
        assertThat(uploaded.segmentCount()).isEqualTo(2);
        assertThat(persisted.get().getStatus()).isEqualTo(TranscriptionStatus.COMPLETED);
        verify(segments).saveAllAndFlush(any());
        verify(transactions, org.mockito.Mockito.times(2)).executeWithoutResult(any());
    }

    @Test
    void deletesNewFileWhenInitialDatabaseInsertFails() {
        DataAccessResourceFailureException failure =
                new DataAccessResourceFailureException("database unavailable");
        when(storage.store(any(UUID.class), eq(file))).thenReturn(stored);
        when(audioFiles.save(any(AudioFile.class))).thenThrow(failure);

        assertThatThrownBy(() -> service.upload(file)).isSameAs(failure);

        verify(storage).delete(stored);
        verifyNoInteractions(whisper);
    }

    @Test
    void preservesStoredFileAndMarksRecordFailedWhenWhisperFails() {
        AtomicReference<AudioFile> persisted = persistSavedAudio();
        TranscriptionException failure = new TranscriptionException(
                "The local Whisper server could not process the audio."
        );
        when(storage.store(any(UUID.class), eq(file))).thenReturn(stored);
        when(whisper.transcribe(any())).thenThrow(failure);

        assertThatThrownBy(() -> service.upload(file))
                .isInstanceOf(TranscriptionException.class)
                .hasMessage(failure.getMessage());

        assertThat(persisted.get().getStatus()).isEqualTo(TranscriptionStatus.FAILED);
        verify(storage, never()).delete(stored);
        verify(segments, never()).saveAllAndFlush(any());
    }

    @Test
    void readsMetadataAndCompletedTranscriptAsDtos() {
        UUID audioId = UUID.randomUUID();
        AudioFile audio = audio(audioId);
        audio.markCompleted(10_200, "en");
        List<TranscriptSegment> storedSegments = List.of(
                new TranscriptSegment(audio, 0, 0, 4_800, "Hello"),
                new TranscriptSegment(audio, 1, 4_800, 10_200, "World")
        );
        when(audioFiles.findById(audioId)).thenReturn(Optional.of(audio));
        when(segments.findByAudioFileIdOrderBySegmentIndexAsc(audioId)).thenReturn(storedSegments);
        when(segments.findAtTime(audioId, 4_800)).thenReturn(Optional.of(storedSegments.get(1)));

        AudioMetadataDto metadata = service.getAudio(audioId);
        TranscriptDto transcript = service.getTranscript(audioId);
        TranscriptSegmentDto segment = service.getSegmentAt(audioId, 4_800);

        assertThat(metadata.filename()).isEqualTo("lecture.mp3");
        assertThat(metadata.status()).isEqualTo(TranscriptionStatus.COMPLETED);
        assertThat(transcript.segments()).containsExactly(
                new TranscriptSegmentDto(0, 0, 4_800, "Hello"),
                new TranscriptSegmentDto(1, 4_800, 10_200, "World")
        );
        assertThat(segment).isEqualTo(new TranscriptSegmentDto(1, 4_800, 10_200, "World"));
    }

    private AtomicReference<AudioFile> persistSavedAudio() {
        AtomicReference<AudioFile> persisted = new AtomicReference<>();
        when(audioFiles.save(any(AudioFile.class))).thenAnswer(invocation -> {
            AudioFile audio = invocation.getArgument(0);
            persisted.set(audio);
            return audio;
        });
        when(audioFiles.findById(any(UUID.class)))
                .thenAnswer(invocation -> Optional.ofNullable(persisted.get()));
        return persisted;
    }

    private static AudioFile audio(UUID audioId) {
        return new AudioFile(
                audioId, "lecture.mp3", audioId + ".mp3", audioId + ".mp3",
                "audio/mpeg", 3, TranscriptionStatus.PROCESSING
        );
    }

    private static WhisperResult whisperResult() {
        return new WhisperResult(
                "en", 10_200,
                List.of(
                        new WhisperSegment(0, 0, 4_800, "Hello"),
                        new WhisperSegment(1, 4_800, 10_200, "World")
                )
        );
    }
}
