package com.example.audiotranscription.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "transcript_segments")
public class TranscriptSegment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "audio_file_id", nullable = false)
    private AudioFile audioFile;

    @Column(name = "segment_index", nullable = false)
    private int segmentIndex;

    @Column(name = "start_time_ms", nullable = false)
    private long startTimeMs;

    @Column(name = "end_time_ms", nullable = false)
    private long endTimeMs;

    @Column(nullable = false)
    private String text;

    protected TranscriptSegment() {
    }

    public TranscriptSegment(
            AudioFile audioFile,
            int segmentIndex,
            long startTimeMs,
            long endTimeMs,
            String text
    ) {
        this.audioFile = audioFile;
        this.segmentIndex = segmentIndex;
        this.startTimeMs = startTimeMs;
        this.endTimeMs = endTimeMs;
        this.text = text;
    }

    public Long getId() {
        return id;
    }

    public AudioFile getAudioFile() {
        return audioFile;
    }

    public int getSegmentIndex() {
        return segmentIndex;
    }

    public long getStartTimeMs() {
        return startTimeMs;
    }

    public long getEndTimeMs() {
        return endTimeMs;
    }

    public String getText() {
        return text;
    }
}
