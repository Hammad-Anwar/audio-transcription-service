package com.example.audiotranscription.error;

public class InvalidAudioFileException extends RuntimeException {

    public InvalidAudioFileException(String message) {
        super(message);
    }
}
