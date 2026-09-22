package com.example.audiotranscription.transcription;

import org.springframework.core.io.Resource;

public interface TranscriptionService {

    TranscriptionResult transcribe(Resource audio);
}
