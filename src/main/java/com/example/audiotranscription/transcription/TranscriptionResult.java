package com.example.audiotranscription.transcription;

import java.util.List;

public record TranscriptionResult(
        String language,
        long durationMs,
        List<TranscriptionSegmentResult> segments
) {
    public TranscriptionResult {
        segments = List.copyOf(segments);
    }
}
