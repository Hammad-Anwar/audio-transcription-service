package com.example.audiotranscription.application;

import java.util.List;
import java.util.UUID;

public record TranscriptView(
        UUID audioId,
        String filename,
        Long durationMs,
        String language,
        List<SegmentView> segments
) {
    public TranscriptView {
        segments = List.copyOf(segments);
    }
}
