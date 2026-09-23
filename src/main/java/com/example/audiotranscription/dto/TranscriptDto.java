package com.example.audiotranscription.dto;

import java.util.List;
import java.util.UUID;

public record TranscriptDto(
        UUID audioId,
        String filename,
        Long durationMs,
        String language,
        List<TranscriptSegmentDto> segments
) {
    public TranscriptDto {
        segments = List.copyOf(segments);
    }
}
