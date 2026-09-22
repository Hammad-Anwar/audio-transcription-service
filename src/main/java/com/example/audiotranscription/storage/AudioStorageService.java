package com.example.audiotranscription.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import com.example.audiotranscription.config.StorageProperties;
import com.example.audiotranscription.error.AudioStorageException;
import com.example.audiotranscription.error.InvalidAudioFileException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

@Service
public class AudioStorageService {

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "audio/mpeg",
            "audio/mp3",
            "application/octet-stream"
    );

    private final Path storageRoot;
    private final long maxFileSize;

    public AudioStorageService(StorageProperties properties) {
        this.storageRoot = properties.audioDirectory().toAbsolutePath().normalize();
        this.maxFileSize = properties.maxFileSize().toBytes();
        initializeStorageRoot();
    }

    public StoredAudio store(UUID audioId, MultipartFile file) {
        String originalFilename = validate(audioId, file);
        String storedFilename = audioId + ".mp3";
        Path relativePath = Path.of(storedFilename);
        Path destination = storageRoot.resolve(relativePath).normalize();
        assertWithinStorageRoot(destination);
        if (Files.exists(destination)) {
            throw new InvalidAudioFileException("An audio file with this identifier already exists.");
        }

        Path partial = storageRoot.resolve(storedFilename + ".part").normalize();
        assertWithinStorageRoot(partial);
        try {
            copyToPartial(file, partial);
            Files.move(partial, destination, StandardCopyOption.ATOMIC_MOVE);
            return new StoredAudio(
                    originalFilename,
                    storedFilename,
                    relativePath,
                    destination,
                    file.getContentType(),
                    file.getSize()
            );
        } catch (IOException exception) {
            deletePartialQuietly(partial);
            throw new AudioStorageException("Could not store the uploaded audio file.", exception);
        }
    }

    public void delete(StoredAudio storedAudio) {
        Path path = storedAudio.absolutePath().toAbsolutePath().normalize();
        assertWithinStorageRoot(path);
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            throw new AudioStorageException("Could not delete the stored audio file.", exception);
        }
    }

    private String validate(UUID audioId, MultipartFile file) {
        if (audioId == null) {
            throw new InvalidAudioFileException("Audio identifier is required.");
        }
        if (file == null || file.isEmpty()) {
            throw new InvalidAudioFileException("An MP3 file is required.");
        }
        if (file.getSize() > maxFileSize) {
            throw new InvalidAudioFileException("The audio file exceeds the configured size limit.");
        }

        String originalFilename = safeOriginalFilename(file.getOriginalFilename());
        if (!originalFilename.toLowerCase(Locale.ROOT).endsWith(".mp3")) {
            throw new InvalidAudioFileException("Only MP3 files are currently supported.");
        }
        if (!ALLOWED_CONTENT_TYPES.contains(file.getContentType())) {
            throw new InvalidAudioFileException("The uploaded file has an unsupported content type.");
        }
        if (!hasMp3Header(file)) {
            throw new InvalidAudioFileException("The uploaded file does not contain a valid MP3 header.");
        }
        return originalFilename;
    }

    private static String safeOriginalFilename(String filename) {
        if (!StringUtils.hasText(filename)) {
            throw new InvalidAudioFileException("The uploaded file must have a filename.");
        }
        String normalized = filename.replace('\\', '/');
        String basename = normalized.substring(normalized.lastIndexOf('/') + 1);
        if (!StringUtils.hasText(basename)) {
            throw new InvalidAudioFileException("The uploaded file must have a filename.");
        }
        return basename;
    }

    private static boolean hasMp3Header(MultipartFile file) {
        byte[] header = new byte[3];
        try (InputStream input = file.getInputStream()) {
            if (input.readNBytes(header, 0, header.length) < header.length) {
                return false;
            }
        } catch (IOException exception) {
            throw new InvalidAudioFileException("The uploaded file could not be read.");
        }

        boolean id3 = header[0] == 'I' && header[1] == 'D' && header[2] == '3';
        boolean mpegFrame = (header[0] & 0xff) == 0xff && (header[1] & 0xe0) == 0xe0;
        return id3 || mpegFrame;
    }

    private static void copyToPartial(MultipartFile file, Path partial) throws IOException {
        try (InputStream input = file.getInputStream();
                OutputStream output = Files.newOutputStream(partial, StandardOpenOption.CREATE_NEW)) {
            input.transferTo(output);
        }
    }

    private void initializeStorageRoot() {
        try {
            Files.createDirectories(storageRoot);
            if (!Files.isDirectory(storageRoot)) {
                throw new AudioStorageException("The audio storage path is not a directory.", null);
            }
        } catch (IOException exception) {
            throw new AudioStorageException("Could not initialize the audio storage directory.", exception);
        }
    }

    private void assertWithinStorageRoot(Path path) {
        if (!path.startsWith(storageRoot)) {
            throw new AudioStorageException("Resolved audio path escapes the storage directory.", null);
        }
    }

    private static void deletePartialQuietly(Path partial) {
        try {
            Files.deleteIfExists(partial);
        } catch (IOException ignored) {
            // Preserve the storage failure that caused cleanup.
        }
    }
}
