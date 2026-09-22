package com.example.audiotranscription.storage;

import java.nio.file.Path;

public record StoredAudio(
        String originalFilename,
        String storedFilename,
        Path relativePath,
        Path absolutePath,
        String contentType,
        long size
) {
}
