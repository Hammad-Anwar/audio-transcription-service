package com.example.audiotranscription.error;

import java.util.UUID;

public class TranscriptNotReadyException extends RuntimeException {

    private final UUID audioId;

    public TranscriptNotReadyException(UUID audioId) {
        super("The transcription is still processing.");
        this.audioId = audioId;
    }

    public UUID getAudioId() {
        return audioId;
    }
}
