package com.example.audiotranscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.example.audiotranscription.model.TranscriptionStatus;
import com.example.audiotranscription.error.TranscriptionException;
import com.example.audiotranscription.repository.AudioFileRepository;
import com.example.audiotranscription.repository.TranscriptSegmentRepository;
import com.example.audiotranscription.whisper.WhisperResult;
import com.example.audiotranscription.whisper.WhisperSegment;
import com.example.audiotranscription.whisper.WhisperTranscriptionService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.Resource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class AudioWorkflowIntegrationTest extends PostgresIntegrationTestBase {

    private static final byte[] MP3_BYTES = new byte[] {
            'I', 'D', '3', 4, 0, 0, 0, 0, 0, 0, 1, 2, 3
    };

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AudioFileRepository audioFiles;

    @MockitoSpyBean
    private TranscriptSegmentRepository segments;

    @MockitoBean
    private WhisperTranscriptionService transcriptionService;

    @BeforeEach
    void clearPersistentState() throws Exception {
        segments.deleteAll();
        audioFiles.deleteAll();
        try (var paths = Files.list(STORAGE_ROOT)) {
            for (var path : paths.toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @Test
    void uploadsAndRetrievesTimestampedTranscript() throws Exception {
        when(transcriptionService.transcribe(any(Resource.class))).thenReturn(new WhisperResult(
                "en",
                10_200,
                List.of(
                        new WhisperSegment(0, 0, 4_800, "Hello"),
                        new WhisperSegment(1, 4_800, 10_200, "world")
                )
        ));

        MvcResult upload = mockMvc.perform(multipart("/api/v1/audio")
                        .file(new MockMultipartFile(
                                "file", "lecture.mp3", "audio/mpeg", MP3_BYTES
                        )))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.segmentCount").value(2))
                .andReturn();

        UUID audioId = uploadedId(upload);
        mockMvc.perform(get("/api/v1/audio/{id}/transcript", audioId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.segments[0].startTimeMs").value(0))
                .andExpect(jsonPath("$.segments[1].startTimeMs").value(4_800));
        mockMvc.perform(get("/api/v1/audio/{id}/transcript/at", audioId)
                        .param("timeMs", "4800"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value("world"));

        assertThat(Files.readAllBytes(STORAGE_ROOT.resolve(audioId + ".mp3")))
                .isEqualTo(MP3_BYTES);
        assertThat(audioFiles.findById(audioId).orElseThrow().getStatus())
                .isEqualTo(TranscriptionStatus.COMPLETED);
        assertThat(segments.findByAudioFileIdOrderBySegmentIndexAsc(audioId))
                .hasSize(2);
    }

    @Test
    void preservesFileAndFailedStatusWhenWhisperFails() throws Exception {
        when(transcriptionService.transcribe(any(Resource.class)))
                .thenThrow(new TranscriptionException(
                        "The local Whisper server could not process the audio.",
                        new IllegalStateException("Whisper unavailable")
                ));

        MvcResult upload = mockMvc.perform(multipart("/api/v1/audio")
                        .file(new MockMultipartFile(
                                "file", "lecture.mp3", "audio/mpeg", MP3_BYTES
                        )))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("TRANSCRIPTION_ERROR"))
                .andExpect(jsonPath("$.audioId").isNotEmpty())
                .andReturn();

        UUID audioId = uploadedId(upload);
        assertThat(Files.readAllBytes(STORAGE_ROOT.resolve(audioId + ".mp3")))
                .isEqualTo(MP3_BYTES);
        assertThat(audioFiles.findById(audioId).orElseThrow().getStatus())
                .isEqualTo(TranscriptionStatus.FAILED);

        mockMvc.perform(get("/api/v1/audio/{id}/transcript", audioId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("TRANSCRIPTION_FAILED"));
    }

    @Test
    void rollsBackTranscriptCompletionAndMarksAudioFailedWhenSegmentPersistenceFails()
            throws Exception {
        when(transcriptionService.transcribe(any(Resource.class))).thenReturn(new WhisperResult(
                "en",
                1_000,
                List.of(new WhisperSegment(0, 0, 1_000, "Hello"))
        ));
        doThrow(new DataIntegrityViolationException("segment write failed"))
                .when(segments).saveAllAndFlush(any());

        mockMvc.perform(multipart("/api/v1/audio")
                        .file(new MockMultipartFile(
                                "file", "lecture.mp3", "audio/mpeg", MP3_BYTES
                        )))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("DATABASE_ERROR"));

        UUID audioId = audioFiles.findAll().getFirst().getId();
        assertThat(audioFiles.findById(audioId).orElseThrow().getStatus())
                .isEqualTo(TranscriptionStatus.FAILED);
        assertThat(segments.findByAudioFileIdOrderBySegmentIndexAsc(audioId)).isEmpty();
    }

    private static UUID uploadedId(MvcResult result) throws Exception {
        Map<String, Object> payload = JsonPath.read(result.getResponse().getContentAsString(), "$" );
        String id = (String) payload.getOrDefault("id", payload.get("audioId"));
        return UUID.fromString(id);
    }
}
