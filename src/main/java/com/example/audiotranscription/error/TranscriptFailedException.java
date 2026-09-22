package com.example.audiotranscription.error;

import java.util.UUID;

public class TranscriptFailedException extends RuntimeException {

    private final UUID audioId;

    public TranscriptFailedException(UUID audioId, String message) {
        super(message);
        this.audioId = audioId;
    }

    public UUID getAudioId() {
        return audioId;
    }
}
