package com.example.audiotranscription.api;

import java.time.Instant;
import java.util.UUID;

import com.example.audiotranscription.error.AudioNotFoundException;
import com.example.audiotranscription.error.AudioStorageException;
import com.example.audiotranscription.error.InvalidAudioFileException;
import com.example.audiotranscription.error.SegmentNotFoundException;
import com.example.audiotranscription.error.TranscriptFailedException;
import com.example.audiotranscription.error.TranscriptNotReadyException;
import com.example.audiotranscription.error.TranscriptionException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(InvalidAudioFileException.class)
    ResponseEntity<ApiError> invalidAudio(InvalidAudioFileException exception) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_AUDIO_FILE", exception.getMessage(), null);
    }

    @ExceptionHandler(AudioNotFoundException.class)
    ResponseEntity<ApiError> audioNotFound(AudioNotFoundException exception) {
        return error(HttpStatus.NOT_FOUND, "AUDIO_NOT_FOUND", exception.getMessage(), exception.getAudioId());
    }

    @ExceptionHandler(SegmentNotFoundException.class)
    ResponseEntity<ApiError> segmentNotFound(SegmentNotFoundException exception) {
        return error(HttpStatus.NOT_FOUND, "SEGMENT_NOT_FOUND", exception.getMessage(), exception.getAudioId());
    }

    @ExceptionHandler(TranscriptNotReadyException.class)
    ResponseEntity<ApiError> transcriptNotReady(TranscriptNotReadyException exception) {
        return error(HttpStatus.CONFLICT, "TRANSCRIPTION_NOT_READY", exception.getMessage(), exception.getAudioId());
    }

    @ExceptionHandler(TranscriptFailedException.class)
    ResponseEntity<ApiError> transcriptFailed(TranscriptFailedException exception) {
        return error(HttpStatus.CONFLICT, "TRANSCRIPTION_FAILED", exception.getMessage(), exception.getAudioId());
    }

    @ExceptionHandler(TranscriptionException.class)
    ResponseEntity<ApiError> transcription(TranscriptionException exception) {
        return error(
                HttpStatus.BAD_GATEWAY,
                "TRANSCRIPTION_ERROR",
                exception.getMessage(),
                exception.getAudioId()
        );
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiError> fileTooLarge(MaxUploadSizeExceededException exception) {
        return error(
                HttpStatus.PAYLOAD_TOO_LARGE,
                "FILE_TOO_LARGE",
                "The audio file exceeds the configured size limit.",
                null
        );
    }

    @ExceptionHandler({
            HandlerMethodValidationException.class,
            ConstraintViolationException.class,
            MissingServletRequestPartException.class,
            MethodArgumentTypeMismatchException.class
    })
    ResponseEntity<ApiError> invalidRequest(Exception exception) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "The request is invalid.", null);
    }

    @ExceptionHandler(AudioStorageException.class)
    ResponseEntity<ApiError> storageFailure(AudioStorageException exception) {
        logger.error("Audio storage failure", exception);
        return error(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "AUDIO_STORAGE_ERROR",
                "The audio file could not be stored.",
                null
        );
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ApiError> databaseFailure(DataAccessException exception) {
        logger.error("Database failure", exception);
        return error(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "DATABASE_ERROR",
                "The audio record could not be processed.",
                null
        );
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpectedFailure(Exception exception) {
        logger.error("Unexpected request failure", exception);
        return error(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                "The request could not be completed.",
                null
        );
    }

    private static ResponseEntity<ApiError> error(
            HttpStatus status,
            String code,
            String message,
            UUID audioId
    ) {
        return ResponseEntity.status(status).body(new ApiError(
                status.value(),
                code,
                message,
                audioId,
                Instant.now()
        ));
    }
}
