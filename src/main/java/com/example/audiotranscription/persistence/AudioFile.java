package com.example.audiotranscription.persistence;

import java.time.Instant;
import java.util.UUID;

import com.example.audiotranscription.domain.TranscriptionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

@Entity
@Table(name = "audio_files")
public class AudioFile {

    @Id
    private UUID id;

    @Column(name = "original_filename", nullable = false, length = 255)
    private String originalFilename;

    @Column(name = "stored_filename", nullable = false, unique = true, length = 255)
    private String storedFilename;

    @Column(name = "file_path", nullable = false, unique = true)
    private String filePath;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "file_size", nullable = false)
    private long fileSize;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(length = 20)
    private String language;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private TranscriptionStatus status;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AudioFile() {
    }

    public AudioFile(
            UUID id,
            String originalFilename,
            String storedFilename,
            String filePath,
            String contentType,
            long fileSize,
            TranscriptionStatus status
    ) {
        this.id = id;
        this.originalFilename = originalFilename;
        this.storedFilename = storedFilename;
        this.filePath = filePath;
        this.contentType = contentType;
        this.fileSize = fileSize;
        this.status = status;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public String getStoredFilename() {
        return storedFilename;
    }

    public String getFilePath() {
        return filePath;
    }

    public String getContentType() {
        return contentType;
    }

    public long getFileSize() {
        return fileSize;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public String getLanguage() {
        return language;
    }

    public TranscriptionStatus getStatus() {
        return status;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    void markCompleted(long durationMs, String language) {
        this.durationMs = durationMs;
        this.language = language;
        this.status = TranscriptionStatus.COMPLETED;
        this.errorMessage = null;
    }

    void markFailed(String errorMessage) {
        this.durationMs = null;
        this.language = null;
        this.status = TranscriptionStatus.FAILED;
        this.errorMessage = errorMessage;
    }
}
