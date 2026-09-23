package com.example.audiotranscription.dto;

import java.util.UUID;

import com.example.audiotranscription.model.TranscriptionStatus;

public record AudioUploadDto(
        UUID id,
        String filename,
        TranscriptionStatus status,
        Long durationMs,
        String language,
        int segmentCount
) {
}
