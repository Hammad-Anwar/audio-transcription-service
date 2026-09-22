package com.example.audiotranscription.api;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import com.example.audiotranscription.application.AudioService;
import com.example.audiotranscription.application.AudioSummary;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.http.HttpStatus;
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
    public ResponseEntity<AudioUploadResponse> upload(
            @RequestParam("file") MultipartFile file
    ) {
        AudioSummary summary = audioService.upload(file);
        URI location = URI.create("/api/v1/audio/" + summary.id());
        return ResponseEntity.created(location).body(AudioUploadResponse.from(summary));
    }

    @GetMapping
    public String testWork() {
        return "It is working";
    }

    @GetMapping("/{audioId}")
    public AudioDetailsResponse getAudio(@PathVariable UUID audioId) {
        return AudioDetailsResponse.from(audioService.getAudio(audioId));
    }

    @GetMapping("/all")
    public List<AudioDetailsResponse> getAllAudio() {
        return audioService.getAllAudio().stream().map(AudioDetailsResponse::from).toList();
    }

    @GetMapping("/{audioId}/transcript")
    public TranscriptResponse getTranscript(@PathVariable UUID audioId) {
        return TranscriptResponse.from(audioService.getTranscript(audioId));
    }

    @GetMapping("/{audioId}/transcript/at")
    public TranscriptSegmentResponse getSegmentAt(
            @PathVariable UUID audioId,
            @RequestParam @PositiveOrZero long timeMs
    ) {
        return TranscriptSegmentResponse.from(audioService.getSegmentAt(audioId, timeMs));
    }
}
