package com.example.audiotranscription.transcription;

public record TranscriptionSegmentResult(
        int index,
        long startTimeMs,
        long endTimeMs,
        String text
) {
}
