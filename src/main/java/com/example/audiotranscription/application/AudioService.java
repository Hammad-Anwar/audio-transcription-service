package com.example.audiotranscription.application;

import java.util.List;
import java.util.UUID;

import com.example.audiotranscription.error.TranscriptionException;
import com.example.audiotranscription.persistence.AudioFile;
import com.example.audiotranscription.persistence.AudioPersistenceService;
import com.example.audiotranscription.persistence.TranscriptSegment;
import com.example.audiotranscription.storage.AudioStorageService;
import com.example.audiotranscription.storage.StoredAudio;
import com.example.audiotranscription.transcription.TranscriptionResult;
import com.example.audiotranscription.transcription.TranscriptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class AudioService {

    private static final Logger logger = LoggerFactory.getLogger(AudioService.class);

    private final AudioStorageService storage;
    private final TranscriptionService transcription;
    private final AudioPersistenceService persistence;

    public AudioService(
            AudioStorageService storage,
            TranscriptionService transcription,
            AudioPersistenceService persistence
    ) {
        this.storage = storage;
        this.transcription = transcription;
        this.persistence = persistence;
    }

    public AudioSummary upload(MultipartFile file) {
        UUID audioId = UUID.randomUUID();
        logger.info("Upload started audioId={}", audioId);
        StoredAudio stored = storage.store(audioId, file);
        logger.info("File stored audioId={} size={}", audioId, stored.size());

        try {
            persistence.createProcessing(audioId, stored);
        } catch (RuntimeException databaseFailure) {
            deleteAfterInitialPersistenceFailure(stored, databaseFailure);
            throw databaseFailure;
        }

        TranscriptionResult result;
        try {
            logger.info("Transcription started audioId={}", audioId);
            result = transcription.transcribe(new FileSystemResource(stored.absolutePath()));
        } catch (TranscriptionException failure) {
            markFailed(audioId, failure.getMessage(), failure);
            logger.warn("Transcription failed audioId={} category=whisper", audioId, failure);
            throw new TranscriptionException(audioId, failure.getMessage(), failure);
        }

        try {
            persistence.complete(audioId, result);
        } catch (RuntimeException databaseFailure) {
            markFailed(audioId, "Transcription persistence could not be completed.", databaseFailure);
            throw databaseFailure;
        }

        logger.info(
                "Transcription completed audioId={} segments={} durationMs={}",
                audioId,
                result.segments().size(),
                result.durationMs()
        );
        return new AudioSummary(
                audioId,
                stored.originalFilename(),
                com.example.audiotranscription.domain.TranscriptionStatus.COMPLETED,
                result.durationMs(),
                result.language(),
                result.segments().size()
        );
    }

    public AudioDetails getAudio(UUID audioId) {
        return toDetails(persistence.requireAudio(audioId));
    }

    public List<AudioDetails> getAllAudio() {
        return persistence.requireAllAudio()
                .stream().map(AudioService::toDetails).toList();
    }


    public TranscriptView getTranscript(UUID audioId) {
        AudioFile audio = persistence.requireAudio(audioId);
        List<SegmentView> segmentViews = persistence.requireTranscript(audioId).stream()
                .map(AudioService::toSegmentView)
                .toList();
        return new TranscriptView(
                audio.getId(),
                audio.getOriginalFilename(),
                audio.getDurationMs(),
                audio.getLanguage(),
                segmentViews
        );
    }

    public SegmentView getSegmentAt(UUID audioId, long timeMs) {
        return toSegmentView(persistence.requireSegmentAt(audioId, timeMs));
    }

    private void deleteAfterInitialPersistenceFailure(
            StoredAudio stored,
            RuntimeException databaseFailure
    ) {
        try {
            storage.delete(stored);
        } catch (RuntimeException cleanupFailure) {
            databaseFailure.addSuppressed(cleanupFailure);
        }
    }

    private void markFailed(UUID audioId, String message, RuntimeException primaryFailure) {
        try {
            persistence.fail(audioId, message);
        } catch (RuntimeException statusFailure) {
            primaryFailure.addSuppressed(statusFailure);
            logger.error("Could not persist FAILED status audioId={}", audioId, statusFailure);
        }
    }

    private static AudioDetails toDetails(AudioFile audio) {
        return new AudioDetails(
                audio.getId(),
                audio.getOriginalFilename(),
                audio.getContentType(),
                audio.getFileSize(),
                audio.getDurationMs(),
                audio.getLanguage(),
                audio.getStatus(),
                audio.getErrorMessage(),
                audio.getCreatedAt(),
                audio.getUpdatedAt()
        );
    }

    private static SegmentView toSegmentView(TranscriptSegment segment) {
        return new SegmentView(
                segment.getSegmentIndex(),
                segment.getStartTimeMs(),
                segment.getEndTimeMs(),
                segment.getText()
        );
    }
}
