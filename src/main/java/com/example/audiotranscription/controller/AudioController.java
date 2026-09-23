package com.example.audiotranscription.controller;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import com.example.audiotranscription.dto.AudioMetadataDto;
import com.example.audiotranscription.dto.AudioUploadDto;
import com.example.audiotranscription.dto.TranscriptDto;
import com.example.audiotranscription.dto.TranscriptSegmentDto;
import com.example.audiotranscription.service.AudioService;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.http.ResponseEntity;
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
public class AudioController {

    private final AudioService audioService;

    public AudioController(AudioService audioService) {
        this.audioService = audioService;
    }

    @PostMapping
    public ResponseEntity<AudioUploadDto> upload(@RequestParam("file") MultipartFile file) {
        AudioUploadDto uploaded = audioService.upload(file);
        URI location = URI.create("/api/v1/audio/" + uploaded.id());
        return ResponseEntity.created(location).body(uploaded);
    }

    @GetMapping("/{audioId}")
    public AudioMetadataDto getAudio(@PathVariable UUID audioId) {
        return audioService.getAudio(audioId);
    }

    @GetMapping
    public List<AudioMetadataDto> getAllAudio() {
        return audioService.getAllAudio();
    }

    @GetMapping("/{audioId}/transcript")
    public TranscriptDto getTranscript(@PathVariable UUID audioId) {
        return audioService.getTranscript(audioId);
    }

    @GetMapping("/{audioId}/transcript/at")
    public TranscriptSegmentDto getSegmentAt(
            @PathVariable UUID audioId,
            @RequestParam @PositiveOrZero long timeMs
    ) {
        return audioService.getSegmentAt(audioId, timeMs);
    }
}
