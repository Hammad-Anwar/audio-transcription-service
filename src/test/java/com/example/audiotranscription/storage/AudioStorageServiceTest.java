package com.example.audiotranscription.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import com.example.audiotranscription.config.StorageProperties;
import com.example.audiotranscription.error.InvalidAudioFileException;
import com.example.audiotranscription.service.AudioStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.unit.DataSize;

class AudioStorageServiceTest {

    @TempDir
    Path tempDirectory;

    private AudioStorageService service;

    @BeforeEach
    void setUp() {
        service = new AudioStorageService(
                new StorageProperties(tempDirectory.resolve("audio"), DataSize.ofBytes(10))
        );
    }

    @Test
    void storesId3Mp3UnderGeneratedUuidWithoutUsingOriginalPath() throws Exception {
        UUID audioId = UUID.randomUUID();
        byte[] content = {'I', 'D', '3', 4, 5, 6};

        StoredAudio stored = service.store(
                audioId,
                mp3("../../lecture.mp3", "audio/mpeg", content)
        );

        assertThat(stored.originalFilename()).isEqualTo("lecture.mp3");
        assertThat(stored.storedFilename()).isEqualTo(audioId + ".mp3");
        assertThat(stored.relativePath()).isEqualTo(Path.of(audioId + ".mp3"));
        assertThat(stored.absolutePath()).startsWith(tempDirectory.resolve("audio"));
        assertThat(Files.readAllBytes(stored.absolutePath())).isEqualTo(content);
    }

    @Test
    void acceptsMpegFrameHeaderAndOctetStreamFallback() {
        byte[] content = {(byte) 0xff, (byte) 0xfb, (byte) 0x90, 0};

        StoredAudio stored = service.store(
                UUID.randomUUID(),
                mp3("voice.MP3", "application/octet-stream", content)
        );

        assertThat(stored.size()).isEqualTo(content.length);
    }

    @Test
    void rejectsEmptyUpload() {
        assertThatThrownBy(() -> service.store(
                UUID.randomUUID(),
                mp3("empty.mp3", "audio/mpeg", new byte[0])
        )).isInstanceOf(InvalidAudioFileException.class);
    }

    @Test
    void rejectsUnsupportedExtension() {
        assertThatThrownBy(() -> service.store(
                UUID.randomUUID(),
                mp3("voice.wav", "audio/mpeg", new byte[] {'I', 'D', '3'})
        )).isInstanceOf(InvalidAudioFileException.class);
    }

    @Test
    void rejectsUnsupportedContentType() {
        assertThatThrownBy(() -> service.store(
                UUID.randomUUID(),
                mp3("voice.mp3", "audio/wav", new byte[] {'I', 'D', '3'})
        )).isInstanceOf(InvalidAudioFileException.class);
    }

    @Test
    void rejectsInvalidMp3Header() {
        assertThatThrownBy(() -> service.store(
                UUID.randomUUID(),
                mp3("voice.mp3", "audio/mpeg", new byte[] {1, 2, 3})
        )).isInstanceOf(InvalidAudioFileException.class);
    }

    @Test
    void rejectsPayloadLargerThanConfiguredLimit() {
        assertThatThrownBy(() -> service.store(
                UUID.randomUUID(),
                mp3("large.mp3", "audio/mpeg", new byte[11])
        )).isInstanceOf(InvalidAudioFileException.class);
    }

    @Test
    void refusesToOverwriteAnExistingUuid() {
        UUID audioId = UUID.randomUUID();
        service.store(audioId, mp3("first.mp3", "audio/mpeg", new byte[] {'I', 'D', '3'}));

        assertThatThrownBy(() -> service.store(
                audioId,
                mp3("second.mp3", "audio/mpeg", new byte[] {'I', 'D', '3', 4})
        )).isInstanceOf(InvalidAudioFileException.class);
    }

    @Test
    void deletesAStoredFile() {
        StoredAudio stored = service.store(
                UUID.randomUUID(),
                mp3("voice.mp3", "audio/mpeg", new byte[] {'I', 'D', '3'})
        );

        service.delete(stored);

        assertThat(stored.absolutePath()).doesNotExist();
    }

    private static MockMultipartFile mp3(String filename, String contentType, byte[] content) {
        return new MockMultipartFile("file", filename, contentType, content);
    }
}
