package com.example.audiotranscription.error;

public class AudioStorageException extends RuntimeException {

    public AudioStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
