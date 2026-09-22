package com.example.audiotranscription.persistence;

import java.util.List;
import java.util.UUID;

import com.example.audiotranscription.storage.StoredAudio;
import com.example.audiotranscription.transcription.TranscriptionResult;

public interface AudioPersistenceService {

    void createProcessing(UUID audioId, StoredAudio storedAudio);

    void complete(UUID audioId, TranscriptionResult result);

    void fail(UUID audioId, String safeMessage);

    AudioFile requireAudio(UUID audioId);

    List<AudioFile> requireAllAudio();

    List<TranscriptSegment> requireTranscript(UUID audioId);

    TranscriptSegment requireSegmentAt(UUID audioId, long timeMs);
}
