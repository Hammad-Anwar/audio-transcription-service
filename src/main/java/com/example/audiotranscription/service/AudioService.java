package com.example.audiotranscription.service;

import java.util.List;
import java.util.UUID;

import com.example.audiotranscription.dto.AudioMetadataDto;
import com.example.audiotranscription.dto.AudioUploadDto;
import com.example.audiotranscription.dto.TranscriptDto;
import com.example.audiotranscription.dto.TranscriptSegmentDto;
import com.example.audiotranscription.config.CacheConfiguration;
import com.example.audiotranscription.error.AudioNotFoundException;
import com.example.audiotranscription.error.SegmentNotFoundException;
import com.example.audiotranscription.error.TranscriptFailedException;
import com.example.audiotranscription.error.TranscriptNotReadyException;
import com.example.audiotranscription.error.TranscriptionException;
import com.example.audiotranscription.model.AudioFile;
import com.example.audiotranscription.model.TranscriptSegment;
import com.example.audiotranscription.model.TranscriptionStatus;
import com.example.audiotranscription.repository.AudioFileRepository;
import com.example.audiotranscription.repository.TranscriptSegmentRepository;
import com.example.audiotranscription.storage.StoredAudio;
import com.example.audiotranscription.whisper.WhisperResult;
import com.example.audiotranscription.whisper.WhisperTranscriptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

@Service
public class AudioService {

    private static final int MAX_ERROR_MESSAGE_LENGTH = 1_000;
    private static final Logger logger = LoggerFactory.getLogger(AudioService.class);

    private final AudioFileRepository audioFiles;
    private final TranscriptSegmentRepository segments;
    private final AudioStorageService storage;
    private final WhisperTranscriptionService whisper;
    private final TransactionTemplate transactions;

    public AudioService(
            AudioFileRepository audioFiles,
            TranscriptSegmentRepository segments,
            AudioStorageService storage,
            WhisperTranscriptionService whisper,
            TransactionTemplate transactions
    ) {
        this.audioFiles = audioFiles;
        this.segments = segments;
        this.storage = storage;
        this.whisper = whisper;
        this.transactions = transactions;
    }

    public AudioUploadDto upload(MultipartFile file) {
        UUID audioId = UUID.randomUUID();
        logger.info("Upload started audioId={}", audioId);
        StoredAudio stored = storage.store(audioId, file);

        try {
            transactions.executeWithoutResult(status -> audioFiles.save(new AudioFile(
                    audioId,
                    stored.originalFilename(),
                    stored.storedFilename(),
                    stored.relativePath().toString(),
                    stored.contentType(),
                    stored.size(),
                    TranscriptionStatus.PROCESSING
            )));
        } catch (RuntimeException databaseFailure) {
            deleteAfterInitialPersistenceFailure(stored, databaseFailure);
            throw databaseFailure;
        }

        WhisperResult result;
        try {
            result = whisper.transcribe(new FileSystemResource(stored.absolutePath()));
        } catch (TranscriptionException failure) {
            markFailed(audioId, failure.getMessage(), failure);
            throw new TranscriptionException(audioId, failure.getMessage(), failure);
        }

        try {
            transactions.executeWithoutResult(status -> complete(audioId, result));
        } catch (RuntimeException databaseFailure) {
            markFailed(audioId, "Transcription persistence could not be completed.", databaseFailure);
            throw databaseFailure;
        }

        return new AudioUploadDto(
                audioId,
                stored.originalFilename(),
                TranscriptionStatus.COMPLETED,
                result.durationMs(),
                result.language(),
                result.segments().size()
        );
    }

    @Cacheable(
            cacheNames = CacheConfiguration.AUDIO_METADATA_CACHE,
            key = "#audioId",
            unless = "#result.status() != T(com.example.audiotranscription.model.TranscriptionStatus).COMPLETED"
    )
    public AudioMetadataDto getAudio(UUID audioId) {
        return toMetadata(requireAudio(audioId));
    }

    public List<AudioMetadataDto> getAllAudio() {
        return audioFiles.findAll().stream().map(AudioService::toMetadata).toList();
    }

    @Cacheable(cacheNames = CacheConfiguration.TRANSCRIPTS_CACHE, key = "#audioId")
    public TranscriptDto getTranscript(UUID audioId) {
        AudioFile audio = requireCompletedAudio(audioId);
        List<TranscriptSegmentDto> transcriptSegments = segments
                .findByAudioFileIdOrderBySegmentIndexAsc(audioId)
                .stream()
                .map(AudioService::toSegmentDto)
                .toList();
        return new TranscriptDto(
                audio.getId(),
                audio.getOriginalFilename(),
                audio.getDurationMs(),
                audio.getLanguage(),
                transcriptSegments
        );
    }

    public TranscriptSegmentDto getSegmentAt(UUID audioId, long timeMs) {
        requireCompletedAudio(audioId);
        return segments.findAtTime(audioId, timeMs)
                .map(AudioService::toSegmentDto)
                .orElseThrow(() -> new SegmentNotFoundException(audioId, timeMs));
    }

    private void complete(UUID audioId, WhisperResult result) {
        AudioFile audio = requireAudio(audioId);
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

    private AudioFile requireCompletedAudio(UUID audioId) {
        AudioFile audio = requireAudio(audioId);
        if (audio.getStatus() == TranscriptionStatus.PROCESSING) {
            throw new TranscriptNotReadyException(audioId);
        }
        if (audio.getStatus() == TranscriptionStatus.FAILED) {
            throw new TranscriptFailedException(audioId, audio.getErrorMessage());
        }
        return audio;
    }

    private AudioFile requireAudio(UUID audioId) {
        return audioFiles.findById(audioId)
                .orElseThrow(() -> new AudioNotFoundException(audioId));
    }

    private void markFailed(UUID audioId, String message, RuntimeException primaryFailure) {
        try {
            transactions.executeWithoutResult(status -> {
                AudioFile audio = requireAudio(audioId);
                if (audio.getStatus() == TranscriptionStatus.PROCESSING) {
                    audio.markFailed(boundMessage(message));
                }
            });
        } catch (RuntimeException statusFailure) {
            primaryFailure.addSuppressed(statusFailure);
            logger.error("Could not persist FAILED status audioId={}", audioId, statusFailure);
        }
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

    private static String boundMessage(String message) {
        String safe = message == null || message.isBlank()
                ? "Transcription failed."
                : message.strip();
        return safe.substring(0, Math.min(safe.length(), MAX_ERROR_MESSAGE_LENGTH));
    }

    private static AudioMetadataDto toMetadata(AudioFile audio) {
        return new AudioMetadataDto(
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

    private static TranscriptSegmentDto toSegmentDto(TranscriptSegment segment) {
        return new TranscriptSegmentDto(
                segment.getSegmentIndex(),
                segment.getStartTimeMs(),
                segment.getEndTimeMs(),
                segment.getText()
        );
    }
}
