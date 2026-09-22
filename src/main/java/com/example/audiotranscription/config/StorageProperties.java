package com.example.audiotranscription.config;

import java.nio.file.Path;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

@ConfigurationProperties("app.storage")
public record StorageProperties(Path audioDirectory, DataSize maxFileSize) {

    public StorageProperties {
        Objects.requireNonNull(audioDirectory, "audioDirectory");
        Objects.requireNonNull(maxFileSize, "maxFileSize");
    }
}
