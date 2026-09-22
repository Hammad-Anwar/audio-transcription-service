package com.example.audiotranscription.api;

import java.util.UUID;

import com.example.audiotranscription.application.AudioSummary;
import com.example.audiotranscription.domain.TranscriptionStatus;

public record AudioUploadResponse(
        UUID id,
        String filename,
        TranscriptionStatus status,
        Long durationMs,
        String language,
        int segmentCount
) {
    static AudioUploadResponse from(AudioSummary summary) {
        return new AudioUploadResponse(
                summary.id(),
                summary.filename(),
                summary.status(),
                summary.durationMs(),
                summary.language(),
                summary.segmentCount()
        );
    }
}
