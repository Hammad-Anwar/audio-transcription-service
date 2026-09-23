package com.example.audiotranscription;

import com.example.audiotranscription.repository.AudioFileRepository;
import com.example.audiotranscription.repository.TranscriptSegmentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none"
})
class AudioTranscriptionApplicationTest {

    @MockitoBean
    private AudioFileRepository audioFiles;

    @MockitoBean
    private TranscriptSegmentRepository segments;

    @MockitoBean
    private TransactionTemplate transactions;

    @Test
    void contextLoads() {
    }
}
