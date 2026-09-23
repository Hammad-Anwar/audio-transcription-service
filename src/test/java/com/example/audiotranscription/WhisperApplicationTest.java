package com.example.audiotranscription;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.audiotranscription.repository.AudioFileRepository;
import com.example.audiotranscription.repository.TranscriptSegmentRepository;
import com.example.audiotranscription.whisper.WhisperTranscriptionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "LOCAL_WHISPER_URL=http://localhost:8082"
})
class WhisperApplicationTest {

    @Autowired
    private WhisperTranscriptionService transcriptionService;

    @MockitoBean
    private AudioFileRepository audioFiles;

    @MockitoBean
    private TranscriptSegmentRepository segments;

    @MockitoBean
    private TransactionTemplate transactions;

    @Test
    void localWhisperIsTheOnlyTranscriptionService() {
        assertThat(transcriptionService)
                .isInstanceOf(WhisperTranscriptionService.class);
    }
}
