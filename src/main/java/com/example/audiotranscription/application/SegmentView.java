package com.example.audiotranscription.application;

public record SegmentView(
        int index,
        long startTimeMs,
        long endTimeMs,
        String text
) {
}
