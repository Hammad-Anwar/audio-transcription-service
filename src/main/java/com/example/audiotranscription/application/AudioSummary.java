package com.example.audiotranscription.application;

import java.util.UUID;

import com.example.audiotranscription.domain.TranscriptionStatus;

public record AudioSummary(
        UUID id,
        String filename,
        TranscriptionStatus status,
        Long durationMs,
        String language,
        int segmentCount
) {
}
