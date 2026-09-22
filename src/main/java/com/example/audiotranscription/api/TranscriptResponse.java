package com.example.audiotranscription.api;

import java.util.List;
import java.util.UUID;

import com.example.audiotranscription.application.TranscriptView;

public record TranscriptResponse(
        UUID audioId,
        String filename,
        Long durationMs,
        String language,
        List<TranscriptSegmentResponse> segments
) {
    static TranscriptResponse from(TranscriptView transcript) {
        return new TranscriptResponse(
                transcript.audioId(),
                transcript.filename(),
                transcript.durationMs(),
                transcript.language(),
                transcript.segments().stream()
                        .map(TranscriptSegmentResponse::from)
                        .toList()
        );
    }
}
