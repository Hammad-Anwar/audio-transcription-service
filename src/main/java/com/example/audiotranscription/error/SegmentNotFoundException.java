package com.example.audiotranscription.error;

import java.util.UUID;

public class SegmentNotFoundException extends RuntimeException {

    private final UUID audioId;
    private final long timeMs;

    public SegmentNotFoundException(UUID audioId, long timeMs) {
        super("No transcript segment exists at the requested timestamp.");
        this.audioId = audioId;
        this.timeMs = timeMs;
    }

    public UUID getAudioId() {
        return audioId;
    }

    public long getTimeMs() {
        return timeMs;
    }
}
