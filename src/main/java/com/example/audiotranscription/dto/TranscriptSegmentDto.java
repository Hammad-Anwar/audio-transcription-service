package com.example.audiotranscription.dto;

public record TranscriptSegmentDto(
        int index,
        long startTimeMs,
        long endTimeMs,
        String text
) {
}
