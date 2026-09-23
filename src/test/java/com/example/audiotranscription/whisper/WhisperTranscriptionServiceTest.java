package com.example.audiotranscription.whisper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.URI;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.sun.net.httpserver.HttpServer;
import com.example.audiotranscription.error.TranscriptionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class WhisperTranscriptionServiceTest {

    private MockRestServiceServer server;
    private WhisperTranscriptionService service;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        service = new WhisperTranscriptionService(
                builder.baseUrl("http://localhost:8082").build()
        );
    }

    @Test
    void postsWhisperMultipartFieldsAndNormalizesVerboseJson() {
        server.expect(requestTo("http://localhost:8082/inference"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.MULTIPART_FORM_DATA))
                .andExpect(request -> {
                    String body = ((MockClientHttpRequest) request)
                            .getBodyAsString(StandardCharsets.ISO_8859_1);
                    assertThat(body)
                            .contains("name=\"file\"", "filename=\"audio.mp3\"")
                            .contains("name=\"response_format\"", "verbose_json")
                            .contains("name=\"language\"", "auto")
                            .contains("name=\"temperature\"", "0.0");
                })
                .andRespond(withSuccess(VERBOSE_JSON, MediaType.APPLICATION_JSON));

        WhisperResult result = service.transcribe(namedMp3());

        assertThat(result).isEqualTo(new WhisperResult(
                "english",
                5_352,
                List.of(
                        new WhisperSegment(0, 0, 4_130, "The sun was setting."),
                        new WhisperSegment(1, 4_130, 5_000, "Empty field.")
                )
        ));
        server.verify();
    }

    @Test
    void rejectsMalformedTimestampSegments() {
        server.expect(requestTo("http://localhost:8082/inference"))
                .andRespond(withSuccess("""
                        {
                          "language": "english",
                          "duration": 1.0,
                          "segments": [
                            {"id": 0, "text": "Bad", "start": 1.0, "end": 1.0}
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> service.transcribe(namedMp3()))
                .isInstanceOf(TranscriptionException.class)
                .hasMessage("The local Whisper response contained invalid segment timestamps.");
    }

    @Test
    void wrapsHttpFailuresWithoutExposingServerDetails() {
        server.expect(requestTo("http://localhost:8082/inference"))
                .andRespond(withServerError().body("internal whisper detail"));

        assertThatThrownBy(() -> service.transcribe(namedMp3()))
                .isInstanceOf(TranscriptionException.class)
                .hasMessage("The local Whisper server could not process the audio.");
    }

    @Test
    void rejectsMissingLanguage() {
        server.expect(requestTo("http://localhost:8082/inference"))
                .andRespond(withSuccess("""
                        {
                          "duration": 1.0,
                          "segments": [
                            {"id": 0, "text": "Text", "start": 0.0, "end": 1.0}
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> service.transcribe(namedMp3()))
                .isInstanceOf(TranscriptionException.class)
                .hasMessage("The local Whisper response did not include a language.");
    }

    @Test
    void rejectsTimelinesThatBecomeInvalidInMilliseconds() {
        server.expect(requestTo("http://localhost:8082/inference"))
                .andRespond(withSuccess("""
                        {
                          "language": "english",
                          "duration": 0.0006,
                          "segments": [
                            {"id": 0, "text": "Too short", "start": 0.0, "end": 0.0004}
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> service.transcribe(namedMp3()))
                .isInstanceOf(TranscriptionException.class)
                .hasMessage("The local Whisper response contained invalid segment timestamps.");
    }

    @Test
    void rejectsOverlappingOrOutOfDurationSegments() {
        server.expect(requestTo("http://localhost:8082/inference"))
                .andRespond(withSuccess("""
                        {
                          "language": "english",
                          "duration": 2.0,
                          "segments": [
                            {"id": 0, "text": "First", "start": 0.0, "end": 1.5},
                            {"id": 1, "text": "Overlap", "start": 1.0, "end": 2.1}
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> service.transcribe(namedMp3()))
                .isInstanceOf(TranscriptionException.class)
                .hasMessage("The local Whisper response contained invalid segment timestamps.");
    }

    @Test
    void timesOutWhenLocalWhisperStopsResponding() throws Exception {
        HttpServer stalledServer = HttpServer.create(new InetSocketAddress(0), 0);
        stalledServer.createContext("/inference", exchange -> {
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        stalledServer.start();
        try {
            URI serverUri = URI.create("http://localhost:" + stalledServer.getAddress().getPort());
            WhisperProperties properties = new WhisperProperties(
                    serverUri,
                    Duration.ofMillis(100),
                    Duration.ofMillis(100)
            );
            RestClient client = new WhisperClientConfiguration()
                    .whisperRestClient(properties);
            WhisperTranscriptionService timeoutService = new WhisperTranscriptionService(client);

            assertThatThrownBy(() -> timeoutService.transcribe(namedMp3()))
                    .isInstanceOf(TranscriptionException.class)
                    .hasMessage("The local Whisper server could not process the audio.");
        } finally {
            stalledServer.stop(0);
        }
    }

    private static Resource namedMp3() {
        return new ByteArrayResource(new byte[] {'I', 'D', '3'}) {
            @Override
            public String getFilename() {
                return "audio.mp3";
            }
        };
    }

    private static final String VERBOSE_JSON = """
            {
              "task": "transcribe",
              "language": "english",
              "duration": 5.3520002365112305,
              "text": " The sun was setting. Empty field.",
              "segments": [
                {
                  "id": 7,
                  "text": " The sun was setting. ",
                  "start": 0.0,
                  "end": 4.13,
                  "tokens": [440, 3295],
                  "words": [{"word": " The", "start": 0.19, "end": 0.24, "t_dtw": -1, "probability": 0.95}],
                  "temperature": 0.0,
                  "avg_logprob": -0.02,
                  "no_speech_prob": 0.003
                },
                {
                  "id": 3,
                  "text": " Empty field. ",
                  "start": 4.13,
                  "end": 5.0,
                  "tokens": [6707, 2519],
                  "words": [{"word": " empty", "start": 4.13, "end": 4.41, "t_dtw": -1, "probability": 0.98}],
                  "temperature": 0.0,
                  "avg_logprob": -0.01,
                  "no_speech_prob": 0.0
                }
              ],
              "detected_language": "english",
              "detected_language_probability": 0.997,
              "language_probabilities": {"en": 0.997}
            }
            """;
}
