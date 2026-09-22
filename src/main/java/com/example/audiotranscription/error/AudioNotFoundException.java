package com.example.audiotranscription.error;

import java.util.UUID;

public class AudioNotFoundException extends RuntimeException {

    private final UUID audioId;

    public AudioNotFoundException(UUID audioId) {
        super("Audio file was not found.");
        this.audioId = audioId;
    }

    public UUID getAudioId() {
        return audioId;
    }
}
