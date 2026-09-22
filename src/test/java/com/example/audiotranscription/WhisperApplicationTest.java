package com.example.audiotranscription;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.audiotranscription.persistence.AudioPersistenceService;
import com.example.audiotranscription.transcription.TranscriptionService;
import com.example.audiotranscription.transcription.whisper.WhisperTranscriptionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "LOCAL_WHISPER_URL=http://localhost:8082"
})
class WhisperApplicationTest {

    @Autowired
    private TranscriptionService transcriptionService;

    @MockitoBean
    private AudioPersistenceService persistenceService;

    @Test
    void localWhisperIsTheOnlyTranscriptionService() {
        assertThat(transcriptionService)
                .isInstanceOf(WhisperTranscriptionService.class);
    }
}
