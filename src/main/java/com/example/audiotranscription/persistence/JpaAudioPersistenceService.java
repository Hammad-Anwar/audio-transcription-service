package com.example.audiotranscription.persistence;

import java.util.List;
import java.util.UUID;

import com.example.audiotranscription.domain.TranscriptionStatus;
import com.example.audiotranscription.error.AudioNotFoundException;
import com.example.audiotranscription.error.SegmentNotFoundException;
import com.example.audiotranscription.error.TranscriptFailedException;
import com.example.audiotranscription.error.TranscriptNotReadyException;
import com.example.audiotranscription.storage.StoredAudio;
import com.example.audiotranscription.transcription.TranscriptionResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JpaAudioPersistenceService implements AudioPersistenceService {

    private static final int MAX_ERROR_MESSAGE_LENGTH = 1_000;

    private final AudioFileRepository audioFiles;
    private final TranscriptSegmentRepository segments;

    public JpaAudioPersistenceService(
            AudioFileRepository audioFiles,
            TranscriptSegmentRepository segments
    ) {
        this.audioFiles = audioFiles;
        this.segments = segments;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void createProcessing(UUID audioId, StoredAudio storedAudio) {
        audioFiles.save(new AudioFile(
                audioId,
                storedAudio.originalFilename(),
                storedAudio.storedFilename(),
                storedAudio.relativePath().toString(),
                storedAudio.contentType(),
                storedAudio.size(),
                TranscriptionStatus.PROCESSING
        ));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(UUID audioId, TranscriptionResult result) {
        AudioFile audio = requireAudioEntity(audioId);
        if (audio.getStatus() != TranscriptionStatus.PROCESSING) {
            throw new IllegalStateException("Only processing audio can be completed.");
        }
        List<TranscriptSegment> transcriptSegments = result.segments().stream()
                .map(segment -> new TranscriptSegment(
                        audio,
                        segment.index(),
                        segment.startTimeMs(),
                        segment.endTimeMs(),
                        segment.text()
                ))
                .toList();
        segments.saveAllAndFlush(transcriptSegments);
        audio.markCompleted(result.durationMs(), result.language());
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(UUID audioId, String safeMessage) {
        AudioFile audio = requireAudioEntity(audioId);
        if (audio.getStatus() == TranscriptionStatus.PROCESSING) {
            audio.markFailed(boundMessage(safeMessage));
        }
    }

    @Override
    @Transactional(readOnly = true)
    public AudioFile requireAudio(UUID audioId) {
        return requireAudioEntity(audioId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AudioFile> requireAllAudio() {
        return requireAllAudioEntity();
    }

    @Override
    @Transactional(readOnly = true)
    public List<TranscriptSegment> requireTranscript(UUID audioId) {
        requireCompletedAudio(audioId);
        return segments.findByAudioFileIdOrderBySegmentIndexAsc(audioId);
    }

    @Override
    @Transactional(readOnly = true)
    public TranscriptSegment requireSegmentAt(UUID audioId, long timeMs) {
        requireCompletedAudio(audioId);
        return segments.findAtTime(audioId, timeMs)
                .orElseThrow(() -> new SegmentNotFoundException(audioId, timeMs));
    }

    private AudioFile requireCompletedAudio(UUID audioId) {
        AudioFile audio = requireAudioEntity(audioId);
        if (audio.getStatus() == TranscriptionStatus.PROCESSING) {
            throw new TranscriptNotReadyException(audioId);
        }
        if (audio.getStatus() == TranscriptionStatus.FAILED) {
            throw new TranscriptFailedException(audioId, audio.getErrorMessage());
        }
        return audio;
    }

    private AudioFile requireAudioEntity(UUID audioId) {
        return audioFiles.findById(audioId)
                .orElseThrow(() -> new AudioNotFoundException(audioId));
    }

    private List<AudioFile> requireAllAudioEntity() {
        return audioFiles.findAll();
    }

    private static String boundMessage(String message) {
        String safe = message == null || message.isBlank()
                ? "Transcription failed."
                : message.strip();
        return safe.substring(0, Math.min(safe.length(), MAX_ERROR_MESSAGE_LENGTH));
    }
}
