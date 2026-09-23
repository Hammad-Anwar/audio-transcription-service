package com.example.audiotranscription.whisper;

public record WhisperSegment(
        int index,
        long startTimeMs,
        long endTimeMs,
        String text
) {
}
