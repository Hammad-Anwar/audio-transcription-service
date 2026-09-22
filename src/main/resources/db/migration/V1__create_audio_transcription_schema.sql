CREATE TABLE audio_files (
    id UUID PRIMARY KEY,
    original_filename VARCHAR(255) NOT NULL,
    stored_filename VARCHAR(255) NOT NULL UNIQUE,
    file_path TEXT NOT NULL UNIQUE,
    content_type VARCHAR(100) NOT NULL,
    file_size BIGINT NOT NULL CHECK (file_size > 0),
    duration_ms BIGINT CHECK (duration_ms > 0),
    language VARCHAR(20),
    status VARCHAR(30) NOT NULL,
    error_message TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE transcript_segments (
    id BIGSERIAL PRIMARY KEY,
    audio_file_id UUID NOT NULL,
    segment_index INTEGER NOT NULL CHECK (segment_index >= 0),
    start_time_ms BIGINT NOT NULL CHECK (start_time_ms >= 0),
    end_time_ms BIGINT NOT NULL CHECK (end_time_ms > start_time_ms),
    text TEXT NOT NULL,
    CONSTRAINT fk_transcript_audio
        FOREIGN KEY (audio_file_id) REFERENCES audio_files(id) ON DELETE CASCADE,
    CONSTRAINT uq_audio_segment UNIQUE (audio_file_id, segment_index)
);

CREATE INDEX idx_transcript_audio_file
    ON transcript_segments(audio_file_id, segment_index);

CREATE INDEX idx_transcript_audio_time
    ON transcript_segments(audio_file_id, start_time_ms, end_time_ms);
