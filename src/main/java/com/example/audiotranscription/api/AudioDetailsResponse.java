package com.example.audiotranscription.api;

import java.time.Instant;
import java.util.UUID;

import com.example.audiotranscription.application.AudioDetails;
import com.example.audiotranscription.domain.TranscriptionStatus;

public record AudioDetailsResponse(
        UUID id,
        String filename,
        String contentType,
        long fileSize,
        Long durationMs,
        String language,
        TranscriptionStatus status,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt
) {
    static AudioDetailsResponse from(AudioDetails details) {
        return new AudioDetailsResponse(
                details.id(),
                details.filename(),
                details.contentType(),
                details.fileSize(),
                details.durationMs(),
                details.language(),
                details.status(),
                details.errorMessage(),
                details.createdAt(),
                details.updatedAt()
        );
    }
}
