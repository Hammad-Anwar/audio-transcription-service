package com.example.audiotranscription.dto;

import java.time.Instant;
import java.util.UUID;

import com.example.audiotranscription.model.TranscriptionStatus;

public record AudioMetadataDto(
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
