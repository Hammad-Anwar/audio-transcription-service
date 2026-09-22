package com.example.audiotranscription.api;

import com.example.audiotranscription.application.SegmentView;

public record TranscriptSegmentResponse(
        int index,
        long startTimeMs,
        long endTimeMs,
        String text
) {
    static TranscriptSegmentResponse from(SegmentView segment) {
        return new TranscriptSegmentResponse(
                segment.index(),
                segment.startTimeMs(),
                segment.endTimeMs(),
                segment.text()
        );
    }
}
