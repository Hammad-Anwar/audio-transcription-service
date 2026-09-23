package com.example.audiotranscription.whisper;

import java.util.List;

public record WhisperResult(
        String language,
        long durationMs,
        List<WhisperSegment> segments
) {
    public WhisperResult {
        segments = List.copyOf(segments);
    }
}
