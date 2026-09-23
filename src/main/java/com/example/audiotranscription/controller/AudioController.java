package com.example.audiotranscription.controller;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import com.example.audiotranscription.dto.AudioMetadataDto;
import com.example.audiotranscription.dto.AudioUploadDto;
import com.example.audiotranscription.dto.TranscriptDto;
import com.example.audiotranscription.dto.TranscriptSegmentDto;
import com.example.audiotranscription.dto.ApiError;
import com.example.audiotranscription.service.AudioService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Validated
@RestController
@RequestMapping("/api/v1/audio")
@Tag(name = "Audio", description = "Upload audio and retrieve transcription results")
public class AudioController {

    private final AudioService audioService;

    public AudioController(AudioService audioService) {
        this.audioService = audioService;
    }

    @Operation(summary = "Upload and transcribe an MP3 file")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Audio transcribed successfully"),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid MP3 file",
                    content = @Content(schema = @Schema(implementation = ApiError.class))
            ),
            @ApiResponse(
                    responseCode = "413",
                    description = "File exceeds the configured size limit",
                    content = @Content(schema = @Schema(implementation = ApiError.class))
            ),
            @ApiResponse(
                    responseCode = "502",
                    description = "Local Whisper transcription failed",
                    content = @Content(schema = @Schema(implementation = ApiError.class))
            )
    })
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<AudioUploadDto> upload(
            @Parameter(description = "MP3 audio file", required = true)
            @RequestParam("file") MultipartFile file
    ) {
        AudioUploadDto uploaded = audioService.upload(file);
        URI location = URI.create("/api/v1/audio/" + uploaded.id());
        return ResponseEntity.created(location).body(uploaded);
    }

    @GetMapping("/{audioId}")
    @Operation(summary = "Get audio metadata")
    public AudioMetadataDto getAudio(@PathVariable UUID audioId) {
        return audioService.getAudio(audioId);
    }

    @GetMapping("/all")
    @Operation(summary = "List all audio metadata")
    public List<AudioMetadataDto> getAllAudio() {
        return audioService.getAllAudio();
    }

    @GetMapping("/{audioId}/transcript")
    @Operation(summary = "Get the completed transcript")
    public TranscriptDto getTranscript(@PathVariable UUID audioId) {
        return audioService.getTranscript(audioId);
    }

    @GetMapping("/{audioId}/transcript/at")
    @Operation(summary = "Get the transcript segment containing a timestamp")
    public TranscriptSegmentDto getSegmentAt(
            @PathVariable UUID audioId,
            @Parameter(description = "Timestamp in milliseconds", example = "4800")
            @RequestParam @PositiveOrZero long timeMs
    ) {
        return audioService.getSegmentAt(audioId, timeMs);
    }
}
