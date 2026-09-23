package com.example.audiotranscription.whisper;

import java.util.ArrayList;
import java.util.List;

import com.example.audiotranscription.error.TranscriptionException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Service
public class WhisperTranscriptionService {

    private final RestClient restClient;

    public WhisperTranscriptionService(
            @Qualifier("whisperRestClient") RestClient restClient
    ) {
        this.restClient = restClient;
    }

    public WhisperResult transcribe(Resource audio) {
        WhisperResponse response;
        try {
            response = restClient.post()
                    .uri("/inference")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(requestParts(audio))
                    .retrieve()
                    .body(WhisperResponse.class);
        } catch (RestClientException exception) {
            throw new TranscriptionException(
                    "The local Whisper server could not process the audio.",
                    exception
            );
        }
        return normalize(response);
    }

    private static MultiValueMap<String, Object> requestParts(Resource audio) {
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("file", audio);
        parts.add("response_format", "verbose_json");
        parts.add("language", "auto");
        parts.add("temperature", "0.0");
        return parts;
    }

    private static WhisperResult normalize(WhisperResponse response) {
        if (response == null
                || response.duration() == null
                || !Double.isFinite(response.duration())
                || response.duration() <= 0) {
            throw new TranscriptionException(
                    "The local Whisper response did not include a valid duration."
            );
        }
        if (response.segments() == null || response.segments().isEmpty()) {
            throw new TranscriptionException(
                    "The local Whisper response did not include timestamped segments."
            );
        }

        long durationMs = toMilliseconds(response.duration());
        if (durationMs <= 0) {
            throw new TranscriptionException(
                    "The local Whisper response did not include a valid duration."
            );
        }
        String language = hasText(response.detectedLanguage())
                ? response.detectedLanguage().trim()
                : hasText(response.language()) ? response.language().trim() : null;
        if (language == null) {
            throw new TranscriptionException(
                    "The local Whisper response did not include a language."
            );
        }

        List<WhisperSegment> normalized =
                new ArrayList<>(response.segments().size());
        double previousEnd = 0;
        long previousEndMs = 0;
        for (int index = 0; index < response.segments().size(); index++) {
            WhisperResponseSegment segment = response.segments().get(index);
            validateSegment(segment, previousEnd, response.duration());
            long startMs = toMilliseconds(segment.start());
            long endMs = toMilliseconds(segment.end());
            if (startMs < previousEndMs || endMs <= startMs || endMs > durationMs) {
                throw invalidTimestamps();
            }
            normalized.add(new WhisperSegment(
                    index,
                    startMs,
                    endMs,
                    segment.text().trim()
            ));
            previousEnd = segment.end();
            previousEndMs = endMs;
        }

        return new WhisperResult(language, durationMs, normalized);
    }

    private static void validateSegment(
            WhisperResponseSegment segment,
            double previousEnd,
            double duration
    ) {
        if (segment == null
                || segment.start() == null
                || segment.end() == null
                || !Double.isFinite(segment.start())
                || !Double.isFinite(segment.end())
                || segment.start() < 0
                || segment.end() <= segment.start()
                || segment.start() < previousEnd
                || segment.end() > duration) {
            throw invalidTimestamps();
        }
        if (!hasText(segment.text())) {
            throw new TranscriptionException(
                    "The local Whisper response contained a blank segment."
            );
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static long toMilliseconds(double seconds) {
        if (seconds > Long.MAX_VALUE / 1_000.0) {
            throw invalidTimestamps();
        }
        return Math.round(seconds * 1_000.0);
    }

    private static TranscriptionException invalidTimestamps() {
        return new TranscriptionException(
                "The local Whisper response contained invalid segment timestamps."
        );
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record WhisperResponse(
            String language,
            @JsonProperty("detected_language") String detectedLanguage,
            Double duration,
            List<WhisperResponseSegment> segments
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record WhisperResponseSegment(
            long id,
            String text,
            Double start,
            Double end
    ) {
    }
}
