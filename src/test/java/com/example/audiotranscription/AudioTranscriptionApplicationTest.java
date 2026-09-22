package com.example.audiotranscription;

import com.example.audiotranscription.persistence.AudioPersistenceService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none"
})
class AudioTranscriptionApplicationTest {

    @MockitoBean
    private AudioPersistenceService persistenceService;

    @Test
    void contextLoads() {
    }
}
