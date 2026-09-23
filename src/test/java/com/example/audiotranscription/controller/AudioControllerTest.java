package com.example.audiotranscription.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.example.audiotranscription.dto.AudioMetadataDto;
import com.example.audiotranscription.dto.AudioUploadDto;
import com.example.audiotranscription.dto.TranscriptDto;
import com.example.audiotranscription.dto.TranscriptSegmentDto;
import com.example.audiotranscription.service.AudioService;
import com.example.audiotranscription.model.TranscriptionStatus;
import com.example.audiotranscription.error.AudioNotFoundException;
import com.example.audiotranscription.error.TranscriptFailedException;
import com.example.audiotranscription.error.TranscriptNotReadyException;
import com.example.audiotranscription.error.TranscriptionException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@WebMvcTest(AudioController.class)
class AudioControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AudioService audioService;

    @Test
    void uploadsMp3AndReturnsCreatedResource() throws Exception {
        UUID audioId = UUID.randomUUID();
        when(audioService.upload(any())).thenReturn(new AudioUploadDto(
                audioId, "lecture.mp3", TranscriptionStatus.COMPLETED,
                10_200L, "en", 2
        ));
        MockMultipartFile file = new MockMultipartFile(
                "file", "lecture.mp3", "audio/mpeg", new byte[] {'I', 'D', '3'}
        );

        mockMvc.perform(multipart("/api/v1/audio").file(file))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/audio/" + audioId))
                .andExpect(jsonPath("$.id").value(audioId.toString()))
                .andExpect(jsonPath("$.filename").value("lecture.mp3"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.segmentCount").value(2));
    }

    @Test
    void returnsPublicAudioMetadataWithoutStoragePaths() throws Exception {
        UUID audioId = UUID.randomUUID();
        when(audioService.getAudio(audioId)).thenReturn(new AudioMetadataDto(
                audioId, "lecture.mp3", "audio/mpeg", 3,
                10_200L, "en", TranscriptionStatus.COMPLETED, null,
                Instant.parse("2026-09-08T12:00:00Z"),
                Instant.parse("2026-09-08T12:01:00Z")
        ));

        mockMvc.perform(get("/api/v1/audio/{audioId}", audioId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(audioId.toString()))
                .andExpect(jsonPath("$.filename").value("lecture.mp3"))
                .andExpect(jsonPath("$.fileSize").value(3))
                .andExpect(jsonPath("$.storedFilename").doesNotExist())
                .andExpect(jsonPath("$.filePath").doesNotExist());
    }

    @Test
    void returnsTranscriptSegmentsInApplicationOrder() throws Exception {
        UUID audioId = UUID.randomUUID();
        when(audioService.getTranscript(audioId)).thenReturn(new TranscriptDto(
                audioId, "lecture.mp3", 10_200L, "en",
                List.of(
                        new TranscriptSegmentDto(0, 0, 4_800, "Hello"),
                        new TranscriptSegmentDto(1, 4_800, 10_200, "World")
                )
        ));

        mockMvc.perform(get("/api/v1/audio/{audioId}/transcript", audioId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.audioId").value(audioId.toString()))
                .andExpect(jsonPath("$.segments[0].index").value(0))
                .andExpect(jsonPath("$.segments[1].index").value(1));
    }

    @Test
    void returnsSegmentAtTimestampAndRejectsNegativeTimestamp() throws Exception {
        UUID audioId = UUID.randomUUID();
        when(audioService.getSegmentAt(audioId, 4_800))
                .thenReturn(new TranscriptSegmentDto(1, 4_800, 10_200, "World"));

        mockMvc.perform(get("/api/v1/audio/{audioId}/transcript/at", audioId)
                        .param("timeMs", "4800"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.index").value(1));
        mockMvc.perform(get("/api/v1/audio/{audioId}/transcript/at", audioId)
                        .param("timeMs", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }

    @Test
    void mapsMissingAudioToNotFound() throws Exception {
        UUID audioId = UUID.randomUUID();
        when(audioService.getAudio(audioId)).thenThrow(new AudioNotFoundException(audioId));

        mockMvc.perform(get("/api/v1/audio/{audioId}", audioId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("AUDIO_NOT_FOUND"))
                .andExpect(jsonPath("$.audioId").value(audioId.toString()));
    }

    @Test
    void mapsProcessingAndFailedTranscriptStatesToConflict() throws Exception {
        UUID processingId = UUID.randomUUID();
        UUID failedId = UUID.randomUUID();
        when(audioService.getTranscript(processingId))
                .thenThrow(new TranscriptNotReadyException(processingId));
        when(audioService.getTranscript(failedId))
                .thenThrow(new TranscriptFailedException(failedId, "Whisper unavailable."));

        mockMvc.perform(get("/api/v1/audio/{audioId}/transcript", processingId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("TRANSCRIPTION_NOT_READY"));
        mockMvc.perform(get("/api/v1/audio/{audioId}/transcript", failedId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("TRANSCRIPTION_FAILED"));
    }

    @Test
    void includesCreatedAudioIdInWhisperFailure() throws Exception {
        UUID audioId = UUID.randomUUID();
        when(audioService.upload(any())).thenThrow(new TranscriptionException(
                audioId,
                "The local Whisper server could not process the audio.",
                null
        ));

        mockMvc.perform(multipart("/api/v1/audio").file(new MockMultipartFile(
                        "file", "lecture.mp3", "audio/mpeg", new byte[] {'I', 'D', '3'}
                )))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("TRANSCRIPTION_ERROR"))
                .andExpect(jsonPath("$.audioId").value(audioId.toString()));
    }

    @Test
    void mapsMultipartLimitToPayloadTooLarge() throws Exception {
        when(audioService.upload(any())).thenThrow(new MaxUploadSizeExceededException(10));

        mockMvc.perform(multipart("/api/v1/audio").file(new MockMultipartFile(
                        "file", "large.mp3", "audio/mpeg", new byte[] {'I', 'D', '3'}
                )))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error").value("FILE_TOO_LARGE"));
    }

    @Test
    void returnsMethodNotAllowedForGetOnUploadEndpoint() throws Exception {
        mockMvc.perform(get("/api/v1/audio"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(jsonPath("$.error").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void returnsNotFoundForUnknownEndpoint() throws Exception {
        mockMvc.perform(get("/api/v1/unknown"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("ENDPOINT_NOT_FOUND"));
    }
}
