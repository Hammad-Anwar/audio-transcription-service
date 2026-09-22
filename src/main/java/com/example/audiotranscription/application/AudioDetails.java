package com.example.audiotranscription.application;

import java.time.Instant;
import java.util.UUID;

import com.example.audiotranscription.domain.TranscriptionStatus;

public record AudioDetails(
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
}
