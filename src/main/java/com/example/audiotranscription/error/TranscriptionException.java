package com.example.audiotranscription.error;

import java.util.UUID;

public class TranscriptionException extends RuntimeException {

    private final UUID audioId;

    public TranscriptionException(String message) {
        this(null, message, null);
    }

    public TranscriptionException(String message, Throwable cause) {
        this(null, message, cause);
    }

    public TranscriptionException(UUID audioId, String message, Throwable cause) {
        super(message, cause);
        this.audioId = audioId;
    }

    public UUID getAudioId() {
        return audioId;
    }
}
