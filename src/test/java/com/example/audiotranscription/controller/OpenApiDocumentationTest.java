package com.example.audiotranscription.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.audiotranscription.service.AudioService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none"
})
@AutoConfigureMockMvc
class OpenApiDocumentationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AudioService audioService;

    @Test
    void publishesTheAudioApiAndMultipartUploadContract() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("Audio Transcription API"))
                .andExpect(jsonPath("$.info.version").value("v1"))
                .andExpect(jsonPath("$.paths['/api/v1/audio'].post").exists())
                .andExpect(jsonPath(
                        "$.paths['/api/v1/audio'].post.requestBody.content['multipart/form-data']"
                ).exists())
                .andExpect(jsonPath("$.paths['/api/v1/audio/all'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/audio/{audioId}'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/audio/{audioId}/transcript'].get").exists())
                .andExpect(jsonPath(
                        "$.paths['/api/v1/audio/{audioId}/transcript/at'].get.parameters[1].name"
                ).value("timeMs"))
                .andExpect(jsonPath("$.components.schemas.ApiError").exists());
    }

    @Test
    void exposesSwaggerUiEntryPoint() throws Exception {
        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(status().is3xxRedirection());
    }
}
