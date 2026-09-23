package com.example.audiotranscription.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfiguration {

    @Bean
    OpenAPI audioTranscriptionOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Audio Transcription API")
                .description("Upload MP3 audio and retrieve timestamped local Whisper transcripts.")
                .version("v1"));
    }
}
