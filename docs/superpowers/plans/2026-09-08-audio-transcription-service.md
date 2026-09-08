# Audio Transcription Service Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a tested Spring Boot API that stores uploaded MP3 files, transcribes them through Spring AI/OpenAI, persists timestamped segments in PostgreSQL, and retrieves transcripts by audio ID and timestamp.

**Architecture:** A thin MVC controller delegates to an orchestration service. Filesystem, Spring AI, and short transactional persistence operations are isolated behind focused services so the remote call never runs inside a database transaction. Provider output is normalized into internal records before persistence.

**Tech Stack:** Java 21, Maven, Spring Boot 4.1.1, Spring AI 2.0.1, Spring Web MVC, Spring Data JPA, Flyway, PostgreSQL, Docker Compose, JUnit, AssertJ, Mockito, Testcontainers.

**Spec:** `docs/superpowers/specs/2026-09-08-audio-transcription-service-design.md`

## Global Constraints

- Use Java 21, Spring Boot 4.1.1, and Spring AI 2.0.1.
- The only backend is Spring Boot; do not introduce Python, queues, microservices, vector databases, or a frontend.
- Store only MP3 files on the local filesystem; never create transcript TXT or JSON files.
- Use synchronous transcription with `whisper-1`, `verbose_json`, and segment timestamp granularity.
- Persist timestamps as integer milliseconds and enum values as strings.
- Flyway owns the schema; Hibernate uses `ddl-auto: validate`.
- Keep remote and filesystem I/O outside database transactions.
- Do not expose entities, internal paths, stack traces, credentials, or full transcript text in logs.
- Use constructor injection, Java records at boundaries, and no Lombok.
- Automated tests must never call the real OpenAI API.

---

### Task 1: Bootstrap the executable application

**Files:**
- Create: `pom.xml`
- Create: `.mvn/wrapper/maven-wrapper.properties`
- Create: `mvnw`
- Create: `mvnw.cmd`
- Create: `.gitignore`
- Create: `src/main/java/com/example/audiotranscription/AudioTranscriptionApplication.java`
- Create: `src/main/resources/application.yml`
- Create: `src/test/resources/application-test.yml`
- Test: `src/test/java/com/example/audiotranscription/AudioTranscriptionApplicationTest.java`

**Interfaces:**
- Consumes: no prior application code.
- Produces: executable `AudioTranscriptionApplication`; Spring configuration with datasource, Flyway, JPA validation, multipart limits, OpenAI key, and storage path.

- [ ] **Step 1: Add Maven dependency management and the failing context test**

Create `pom.xml` with `spring-boot-starter-parent:4.1.1`, Java 21, `spring-ai-bom:2.0.1`, `spring-boot-starter-webmvc`, `spring-boot-starter-data-jpa`, `spring-boot-starter-validation`, `flyway-core`, `flyway-database-postgresql`, PostgreSQL runtime driver, `spring-ai-starter-model-openai`, `spring-boot-starter-test`, `spring-boot-testcontainers`, and Testcontainers PostgreSQL. Configure Surefire through the Spring Boot parent.

Create this test before the application class:

```java
package com.example.audiotranscription;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
    "spring.autoconfigure.exclude="
        + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
        + "org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration"
})
class AudioTranscriptionApplicationTest {
    @Test
    void contextLoads() {
    }
}
```

- [ ] **Step 2: Run the test and verify RED**

Run: `mvn -q -Dtest=AudioTranscriptionApplicationTest test`

Expected: compilation fails because `AudioTranscriptionApplication` does not exist or Spring cannot locate a configuration class.

- [ ] **Step 3: Add the minimal application and configuration**

```java
package com.example.audiotranscription;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AudioTranscriptionApplication {
    public static void main(String[] args) {
        SpringApplication.run(AudioTranscriptionApplication.class, args);
    }
}
```

Set `spring.datasource.*` from `DATABASE_URL`, `DATABASE_USERNAME`, and `DATABASE_PASSWORD`; set `spring.jpa.hibernate.ddl-auto: validate`; enable Flyway; bind both multipart limits to `${AUDIO_MAX_FILE_SIZE:25MB}`; set `spring.ai.openai.api-key: ${OPENAI_API_KEY:}`; and set `app.storage.audio-directory: ${AUDIO_STORAGE_DIRECTORY:./uploads/audio}`.

Add `.gitignore` entries for `target/`, `.idea/`, `.vscode/`, `.env`, and `uploads/` while retaining `.env.example` later.

- [ ] **Step 4: Run the test and verify GREEN**

Run: `mvn -q -Dtest=AudioTranscriptionApplicationTest test`

Expected: PASS with one context test and no attempt to connect to PostgreSQL or OpenAI.

- [ ] **Step 5: Generate and verify the Maven wrapper**

Run: `mvn wrapper:wrapper -Dmaven=3.9.11`

Run: `./mvnw -q -Dtest=AudioTranscriptionApplicationTest test`

Expected: PASS using the repository-owned Maven 3.9.11 wrapper.

- [ ] **Step 6: Commit the bootstrap**

```bash
git add pom.xml .mvn mvnw mvnw.cmd .gitignore src/main src/test
git commit -m "build: bootstrap Spring Boot transcription service"
```

---

### Task 2: Create and verify the PostgreSQL persistence model

**Files:**
- Create: `docker-compose.yml`
- Create: `src/main/resources/db/migration/V1__create_audio_transcription_schema.sql`
- Create: `src/main/java/com/example/audiotranscription/domain/TranscriptionStatus.java`
- Create: `src/main/java/com/example/audiotranscription/persistence/AudioFile.java`
- Create: `src/main/java/com/example/audiotranscription/persistence/TranscriptSegment.java`
- Create: `src/main/java/com/example/audiotranscription/persistence/AudioFileRepository.java`
- Create: `src/main/java/com/example/audiotranscription/persistence/TranscriptSegmentRepository.java`
- Test: `src/test/java/com/example/audiotranscription/persistence/TranscriptRepositoryTest.java`

**Interfaces:**
- Consumes: Spring/JPA/Flyway/Testcontainers configuration from Task 1.
- Produces: `AudioFileRepository`; `TranscriptSegmentRepository.findByAudioFileIdOrderBySegmentIndexAsc(UUID)`; `TranscriptSegmentRepository.findAtTime(UUID,long)`.

- [ ] **Step 1: Write a PostgreSQL repository test that describes ordering and half-open lookup**

Use `@DataJpaTest`, `@ImportAutoConfiguration(FlywayAutoConfiguration.class)`, `@Testcontainers`, and a static `PostgreSQLContainer<?>`. Register datasource properties with `@DynamicPropertySource`. Persist one `AudioFile` and three deliberately out-of-order `TranscriptSegment` rows, then assert:

```java
assertThat(segments.findByAudioFileIdOrderBySegmentIndexAsc(audioId))
    .extracting(TranscriptSegment::getSegmentIndex)
    .containsExactly(0, 1, 2);

assertThat(segments.findAtTime(audioId, 4_800))
    .get()
    .extracting(TranscriptSegment::getSegmentIndex)
    .isEqualTo(1);
```

Add a second test that deletes the audio row, flushes, and asserts its segment rows are gone.

- [ ] **Step 2: Run the repository test and verify RED**

Run: `./mvnw -q -Dtest=TranscriptRepositoryTest test`

Expected: test compilation fails because the entities and repositories do not exist.

- [ ] **Step 3: Add the migration, entities, and repositories**

The migration creates both tables and constraints exactly as specified in the design. Use `TIMESTAMP WITH TIME ZONE`, `CHECK` constraints, a unique `(audio_file_id, segment_index)` constraint, and the two composite indexes.

Implement the status enum:

```java
public enum TranscriptionStatus {
    PROCESSING, COMPLETED, FAILED
}
```

Map `AudioFile` with UUID assigned by the application, `@Enumerated(EnumType.STRING)`, `Instant` timestamps, and lifecycle callbacks. Map only a lazy `@ManyToOne` from `TranscriptSegment` to `AudioFile`; do not add a collection to `AudioFile`.

```java
public interface TranscriptSegmentRepository
        extends JpaRepository<TranscriptSegment, Long> {
    List<TranscriptSegment> findByAudioFileIdOrderBySegmentIndexAsc(UUID audioId);

    @Query("""
        select s from TranscriptSegment s
        where s.audioFile.id = :audioId
          and s.startTimeMs <= :timeMs
          and s.endTimeMs > :timeMs
        order by s.segmentIndex
        """)
    List<TranscriptSegment> findAtTimeCandidates(
        UUID audioId, long timeMs, Pageable pageable);

    default Optional<TranscriptSegment> findAtTime(UUID audioId, long timeMs) {
        return findAtTimeCandidates(audioId, timeMs, PageRequest.of(0, 1))
            .stream()
            .findFirst();
    }
}
```

- [ ] **Step 4: Run repository tests and verify GREEN**

Run: `./mvnw -q -Dtest=TranscriptRepositoryTest test`

Expected: PASS against PostgreSQL; Flyway migrates successfully and Hibernate validates the schema.

- [ ] **Step 5: Add local PostgreSQL Compose and commit**

Create a `postgres:17-alpine` service named `postgres`, database `audio_transcription`, user `audio_user`, password `audio_password`, port `5432:5432`, a named volume, and a `pg_isready` health check.

```bash
git add docker-compose.yml src/main src/test
git commit -m "feat: add PostgreSQL transcription schema"
```

---

### Task 3: Validate and store MP3 uploads safely

**Files:**
- Create: `src/main/java/com/example/audiotranscription/config/StorageProperties.java`
- Create: `src/main/java/com/example/audiotranscription/storage/StoredAudio.java`
- Create: `src/main/java/com/example/audiotranscription/storage/AudioStorageService.java`
- Create: `src/main/java/com/example/audiotranscription/error/InvalidAudioFileException.java`
- Create: `src/main/java/com/example/audiotranscription/error/AudioStorageException.java`
- Test: `src/test/java/com/example/audiotranscription/storage/AudioStorageServiceTest.java`

**Interfaces:**
- Consumes: `app.storage.audio-directory` configuration from Task 1.
- Produces: `StoredAudio store(UUID audioId, MultipartFile file)` and `void delete(StoredAudio storedAudio)`.

- [ ] **Step 1: Write failing storage behavior tests**

Use `@TempDir`, instantiate the service directly, and create `MockMultipartFile` values. Cover a valid ID3-prefixed MP3, an MPEG-frame-prefixed MP3, empty content, `.wav`, unacceptable content type, invalid header, and duplicate UUID protection.

For the success case, assert real filesystem behavior:

```java
StoredAudio stored = service.store(audioId, mp3("lecture.mp3", id3Bytes()));

assertThat(stored.storedFilename()).isEqualTo(audioId + ".mp3");
assertThat(stored.relativePath()).isEqualTo(Path.of(audioId + ".mp3"));
assertThat(Files.readAllBytes(stored.absolutePath())).isEqualTo(id3Bytes());
```

For deletion, store a real file, call `delete`, and assert `Files.notExists(stored.absolutePath())`.

- [ ] **Step 2: Run the storage test and verify RED**

Run: `./mvnw -q -Dtest=AudioStorageServiceTest test`

Expected: compilation fails because storage types do not exist.

- [ ] **Step 3: Implement minimal validation and atomic storage**

```java
@ConfigurationProperties("app.storage")
public record StorageProperties(Path audioDirectory, DataSize maxFileSize) {
    public StorageProperties {
        Objects.requireNonNull(audioDirectory, "audioDirectory");
        Objects.requireNonNull(maxFileSize, "maxFileSize");
    }
}

public record StoredAudio(
    String originalFilename,
    String storedFilename,
    Path relativePath,
    Path absolutePath,
    String contentType,
    long size
) {}
```

`AudioStorageService` normalizes and creates the root in its constructor. `store` validates filename, allowed MIME types, `file.getSize() <= maxFileSize.toBytes()`, and the first three bytes (`ID3` or MPEG sync `0xFF` followed by `(byte & 0xE0) == 0xE0`). It writes to a sibling `.part` file with `CREATE_NEW`, then atomically moves to `{uuid}.mp3`; it deletes the partial file on failure. It rejects an existing final path rather than overwriting it. Add `app.storage.max-file-size: ${AUDIO_MAX_FILE_SIZE:25MB}` to `application.yml` so application and multipart validation share the same environment value.

- [ ] **Step 4: Run storage tests and verify GREEN**

Run: `./mvnw -q -Dtest=AudioStorageServiceTest test`

Expected: PASS with all filesystem assertions using the real temporary directory.

- [ ] **Step 5: Commit storage**

```bash
git add src/main/java/com/example/audiotranscription/config src/main/java/com/example/audiotranscription/storage src/main/java/com/example/audiotranscription/error src/test/java/com/example/audiotranscription/storage
git commit -m "feat: validate and store MP3 uploads"
```

---

### Task 4: Normalize Spring AI timestamped transcription output

**Files:**
- Create: `src/main/java/com/example/audiotranscription/transcription/TranscriptionResult.java`
- Create: `src/main/java/com/example/audiotranscription/transcription/TranscriptionSegmentResult.java`
- Create: `src/main/java/com/example/audiotranscription/transcription/OpenAiTranscriptionMapper.java`
- Create: `src/main/java/com/example/audiotranscription/transcription/TranscriptionService.java`
- Create: `src/main/java/com/example/audiotranscription/error/TranscriptionException.java`
- Test: `src/test/java/com/example/audiotranscription/transcription/OpenAiTranscriptionMapperTest.java`
- Test: `src/test/java/com/example/audiotranscription/transcription/TranscriptionServiceTest.java`

**Interfaces:**
- Consumes: Spring AI `TranscriptionModel`, `AudioTranscriptionResponse`, and OpenAI response metadata.
- Produces: `TranscriptionResult transcribe(Resource audio)` returning provider-independent millisecond segments.

- [ ] **Step 1: Write failing mapper tests with literal timestamp expectations**

Construct real OpenAI SDK `TranscriptionSegment` values through their builders and real `OpenAiAudioTranscriptionResponseMetadata`. Assert that `4.823f` becomes `4823L`, provider IDs are replaced by list position, and language/duration are retained.

```java
assertThat(result.durationMs()).isEqualTo(15_600L);
assertThat(result.segments()).containsExactly(
    new TranscriptionSegmentResult(0, 0, 4_823, "Hello everyone."),
    new TranscriptionSegmentResult(1, 4_823, 10_200, "Today we discuss databases.")
);
```

Add separate cases for null/empty segments, blank text, negative start, end not after start, and decreasing starts. Each must throw `TranscriptionException`.

- [ ] **Step 2: Run the mapper tests and verify RED**

Run: `./mvnw -q -Dtest=OpenAiTranscriptionMapperTest test`

Expected: compilation fails because normalization types do not exist.

- [ ] **Step 3: Implement the internal records and mapper**

```java
public record TranscriptionResult(
    String language,
    long durationMs,
    List<TranscriptionSegmentResult> segments
) {
    public TranscriptionResult {
        segments = List.copyOf(segments);
    }
}

public record TranscriptionSegmentResult(
    int index, long startTimeMs, long endTimeMs, String text
) {}
```

The package-private mapper verifies `metadata instanceof OpenAiAudioTranscriptionResponseMetadata`, requires a positive duration and nonempty typed `TranscriptionSegment` list, checks every invariant, trims segment text, and converts seconds using `Math.round(seconds * 1000.0)`.

- [ ] **Step 4: Verify mapper GREEN, then write a failing service delegation test**

Run: `./mvnw -q -Dtest=OpenAiTranscriptionMapperTest test`

Expected: PASS.

Mock only `TranscriptionModel`, return a real `AudioTranscriptionResponse` with OpenAI metadata, call the service with a `ByteArrayResource`, and assert the real normalized result. Capture the prompt and assert its options request model `whisper-1`, `AudioResponseFormat.VERBOSE_JSON`, segment granularity, and temperature zero.

- [ ] **Step 5: Run service test RED, implement the Spring AI call, then verify GREEN**

Run before implementation: `./mvnw -q -Dtest=TranscriptionServiceTest test`

Expected: compilation fails because `TranscriptionService` does not exist.

```java
@Service
public class TranscriptionService {
    private final TranscriptionModel model;
    private final OpenAiTranscriptionMapper mapper;

    public TranscriptionResult transcribe(Resource audio) {
        var options = OpenAiAudioTranscriptionOptions.builder()
            .model("whisper-1")
            .responseFormat(AudioResponseFormat.VERBOSE_JSON)
            .timestampGranularities(List.of(TimestampGranularity.SEGMENT))
            .temperature(0.0f)
            .build();
        return mapper.map(model.call(new AudioTranscriptionPrompt(audio, options)));
    }
}
```

Wrap provider runtime failures in `TranscriptionException` without embedding credentials or transcript content.

Run after implementation: `./mvnw -q -Dtest=OpenAiTranscriptionMapperTest,TranscriptionServiceTest test`

Expected: PASS.

- [ ] **Step 6: Commit transcription adapter**

```bash
git add src/main/java/com/example/audiotranscription/transcription src/main/java/com/example/audiotranscription/error/TranscriptionException.java src/test/java/com/example/audiotranscription/transcription
git commit -m "feat: normalize timestamped Spring AI transcriptions"
```

---

### Task 5: Implement transactional state changes and upload orchestration

**Files:**
- Create: `src/main/java/com/example/audiotranscription/persistence/AudioPersistenceService.java`
- Create: `src/main/java/com/example/audiotranscription/application/AudioService.java`
- Create: `src/main/java/com/example/audiotranscription/application/AudioSummary.java`
- Create: `src/main/java/com/example/audiotranscription/application/AudioDetails.java`
- Create: `src/main/java/com/example/audiotranscription/application/TranscriptView.java`
- Create: `src/main/java/com/example/audiotranscription/application/SegmentView.java`
- Create: `src/main/java/com/example/audiotranscription/error/AudioNotFoundException.java`
- Create: `src/main/java/com/example/audiotranscription/error/TranscriptNotReadyException.java`
- Test: `src/test/java/com/example/audiotranscription/application/AudioServiceTest.java`
- Test: `src/test/java/com/example/audiotranscription/persistence/AudioPersistenceServiceTest.java`

**Interfaces:**
- Consumes: `StoredAudio`, `TranscriptionResult`, both repositories.
- Produces: transactional `createProcessing`, `complete`, `fail`, and query methods; `AudioSummary upload(MultipartFile)`, `AudioDetails getAudio(UUID)`, `TranscriptView getTranscript(UUID)`, and `SegmentView getSegmentAt(UUID,long)`.

- [ ] **Step 1: Write failing orchestration tests for success and compensation**

Mock the storage, transcription, and persistence boundaries. On success, assert the returned summary literals and use `InOrder` only to prove the externally meaningful sequence: store, create `PROCESSING`, transcribe, complete.

Add failure tests:

```java
when(persistence.createProcessing(audioId, stored)).thenThrow(databaseFailure);
assertThatThrownBy(() -> service.upload(file)).isSameAs(databaseFailure);
verify(storage).delete(stored);
verifyNoInteractions(transcription);
```

When transcription throws, assert `persistence.fail(audioId, safeMessage)` is called, `storage.delete` is not called, and the rethrown `TranscriptionException` carries `audioId`.

- [ ] **Step 2: Run the orchestration test and verify RED**

Run: `./mvnw -q -Dtest=AudioServiceTest test`

Expected: compilation fails because application services and summaries do not exist.

- [ ] **Step 3: Implement minimal orchestration outside transactions**

```java
public record AudioSummary(
    UUID id,
    String filename,
    TranscriptionStatus status,
    Long durationMs,
    String language,
    int segmentCount
) {}

public record AudioDetails(
    UUID id, String filename, String contentType, long fileSize,
    Long durationMs, String language, TranscriptionStatus status,
    String errorMessage, Instant createdAt, Instant updatedAt
) {}

public record SegmentView(
    int index, long startTimeMs, long endTimeMs, String text
) {}

public record TranscriptView(
    UUID audioId, String filename, Long durationMs, String language,
    List<SegmentView> segments
) {
    public TranscriptView {
        segments = List.copyOf(segments);
    }
}
```

`AudioService.upload(MultipartFile)` generates the UUID, stores the file, creates the row, passes a `FileSystemResource` to transcription, and completes persistence. It deletes the file only when initial persistence fails. It marks an existing row failed on transcription error and returns or throws application types without using `@Transactional`.

Add parameterized SLF4J events without audio or transcript content: `Upload started audioId={}`, `File stored audioId={} size={}`, `Transcription started audioId={}`, `Transcription completed audioId={} segments={} durationMs={}`, and `Transcription failed audioId={} category={}`. Log the throwable only at the internal failure boundary; REST responses remain sanitized.

- [ ] **Step 4: Run orchestration tests and verify GREEN**

Run: `./mvnw -q -Dtest=AudioServiceTest test`

Expected: PASS.

- [ ] **Step 5: Write failing transactional persistence tests**

Using the PostgreSQL Testcontainers base from Task 2, call `createProcessing`, `complete`, and `fail` through the real Spring bean. Assert that completion persists all segments and the final metadata, failure clears completion fields and records a bounded message, and a duplicate segment causes the completion transaction to roll back without changing `PROCESSING`.

- [ ] **Step 6: Run persistence service test RED, implement, and verify GREEN**

Run before implementation: `./mvnw -q -Dtest=AudioPersistenceServiceTest test`

Expected: compilation fails because `AudioPersistenceService` does not exist.

Implement public methods with separate `@Transactional` boundaries:

```java
AudioFile createProcessing(UUID audioId, StoredAudio storedAudio);
AudioFile complete(UUID audioId, TranscriptionResult result);
void fail(UUID audioId, String safeMessage);
AudioFile requireAudio(UUID audioId);
List<TranscriptSegment> requireTranscript(UUID audioId);
TranscriptSegment requireSegmentAt(UUID audioId, long timeMs);
```

`complete` loads the managed audio row, requires `PROCESSING`, bulk-saves mapped segments, sets duration/language/status, and clears errors. Query methods distinguish unknown, processing, failed, and no-segment states using the application exceptions.

Run after implementation: `./mvnw -q -Dtest=AudioPersistenceServiceTest,AudioServiceTest test`

Expected: PASS.

- [ ] **Step 7: Commit workflow**

```bash
git add src/main/java/com/example/audiotranscription/application src/main/java/com/example/audiotranscription/persistence/AudioPersistenceService.java src/main/java/com/example/audiotranscription/error src/test/java/com/example/audiotranscription/application src/test/java/com/example/audiotranscription/persistence/AudioPersistenceServiceTest.java
git commit -m "feat: orchestrate transactional transcription workflow"
```

---

### Task 6: Expose the REST API and consistent failures

**Files:**
- Create: `src/main/java/com/example/audiotranscription/api/AudioController.java`
- Create: `src/main/java/com/example/audiotranscription/api/AudioUploadResponse.java`
- Create: `src/main/java/com/example/audiotranscription/api/AudioDetailsResponse.java`
- Create: `src/main/java/com/example/audiotranscription/api/TranscriptResponse.java`
- Create: `src/main/java/com/example/audiotranscription/api/TranscriptSegmentResponse.java`
- Create: `src/main/java/com/example/audiotranscription/api/ApiError.java`
- Create: `src/main/java/com/example/audiotranscription/api/GlobalExceptionHandler.java`
- Create: `src/main/java/com/example/audiotranscription/error/SegmentNotFoundException.java`
- Test: `src/test/java/com/example/audiotranscription/api/AudioControllerTest.java`

**Interfaces:**
- Consumes: `AudioService.upload`, `getAudio`, `getTranscript`, and `getSegmentAt`.
- Produces: the four `/api/v1/audio` HTTP contracts and the shared JSON error contract.

- [ ] **Step 1: Write failing MVC contract tests**

Use `@WebMvcTest(AudioController.class)` and `@MockitoBean AudioService`. Cover:

- multipart upload returns `201`, a `Location` header, and exact summary JSON;
- metadata returns public fields without `storedFilename` or `filePath`;
- transcript segments retain order;
- timestamp lookup rejects negative values with `400`;
- unknown audio returns `404` and `AUDIO_NOT_FOUND`;
- not-ready and failed transcripts return `409` with distinct codes;
- transcription exceptions with an audio ID return `502` and include the ID;
- multipart size exceptions return `413`.

Example assertions:

```java
mockMvc.perform(multipart("/api/v1/audio").file(validMp3))
    .andExpect(status().isCreated())
    .andExpect(header().string("Location", "/api/v1/audio/" + audioId))
    .andExpect(jsonPath("$.id").value(audioId.toString()))
    .andExpect(jsonPath("$.status").value("COMPLETED"));
```

- [ ] **Step 2: Run MVC tests and verify RED**

Run: `./mvnw -q -Dtest=AudioControllerTest test`

Expected: compilation fails because API types do not exist.

- [ ] **Step 3: Implement DTO mapping, controller, and advice**

Use records whose fields exactly match the design. The controller validates `timeMs` with `@PositiveOrZero` and returns `ResponseEntity.created(location)` for uploads.

```java
public record ApiError(
    int status,
    String error,
    String message,
    UUID audioId,
    Instant timestamp
) {}
```

`GlobalExceptionHandler` maps each known exception explicitly and handles unexpected failures as `500 INTERNAL_ERROR` with a generic message. It never serializes exception classes or stack traces.

- [ ] **Step 4: Run MVC tests and verify GREEN**

Run: `./mvnw -q -Dtest=AudioControllerTest test`

Expected: PASS with exact status, header, payload, and error assertions.

- [ ] **Step 5: Commit API**

```bash
git add src/main/java/com/example/audiotranscription/api src/main/java/com/example/audiotranscription/error/SegmentNotFoundException.java src/test/java/com/example/audiotranscription/api
git commit -m "feat: expose audio transcription REST API"
```

---

### Task 7: Verify the complete workflow with real storage and PostgreSQL

**Files:**
- Create: `src/test/java/com/example/audiotranscription/AudioWorkflowIntegrationTest.java`
- Create: `src/test/java/com/example/audiotranscription/PostgresIntegrationTestBase.java`

**Interfaces:**
- Consumes: all production components; replaces only `TranscriptionService` with a Spring `@MockitoBean`.
- Produces: end-to-end proof of the MVP workflow without external OpenAI access.

- [ ] **Step 1: Write a complete success-path integration test**

Start PostgreSQL with a shared Testcontainers base, inject a dynamic temporary storage directory, use real MockMvc/storage/persistence/controller components, and mock only `TranscriptionService`.

Upload an MP3 fixture, then assert:

```java
mockMvc.perform(get("/api/v1/audio/{id}/transcript", audioId))
    .andExpect(status().isOk())
    .andExpect(jsonPath("$.segments[0].startTimeMs").value(0))
    .andExpect(jsonPath("$.segments[1].startTimeMs").value(4_800));

assertThat(Files.readAllBytes(storageRoot.resolve(audioId + ".mp3")))
    .isEqualTo(mp3Bytes);
assertThat(audioFiles.findById(audioId).orElseThrow().getStatus())
    .isEqualTo(TranscriptionStatus.COMPLETED);
```

Capture the ID from the upload response rather than preselecting it.

- [ ] **Step 2: Run the success integration test**

Run: `./mvnw -q -Dtest=AudioWorkflowIntegrationTest#uploadsAndRetrievesTimestampedTranscript test`

Expected: PASS, proving the independently test-driven components are wired into one real storage/database/HTTP workflow.

- [ ] **Step 3: Add and pass the provider-failure integration case**

Configure the mocked transcription service to throw. Assert the upload returns `502` with `audioId`, the real file remains present, the real database row is `FAILED`, and the transcript endpoint returns `409 TRANSCRIPTION_FAILED`.

Run: `./mvnw -q -Dtest=AudioWorkflowIntegrationTest test`

Expected: PASS for success and failure paths.

- [ ] **Step 4: Run the complete automated suite**

Run: `./mvnw verify`

Expected: BUILD SUCCESS; unit/MVC tests do not access OpenAI, and PostgreSQL tests use Testcontainers.

- [ ] **Step 5: Commit integration verification**

```bash
git add src/test/java/com/example/audiotranscription
git commit -m "test: verify complete transcription workflow"
```

---

### Task 8: Document local operation and final verification

**Files:**
- Create: `.env.example`
- Create: `README.md`
- Modify: `src/main/resources/application.yml`

**Interfaces:**
- Consumes: final configuration and API contracts.
- Produces: reproducible setup, run, test, and manual smoke-test instructions.

- [ ] **Step 1: Write the concrete environment example and README**

`.env.example` contains only:

```dotenv
DATABASE_URL=jdbc:postgresql://localhost:5432/audio_transcription
DATABASE_USERNAME=audio_user
DATABASE_PASSWORD=audio_password
OPENAI_API_KEY=replace-with-your-key
AUDIO_STORAGE_DIRECTORY=./uploads/audio
AUDIO_MAX_FILE_SIZE=25MB
```

README sections cover architecture, prerequisites (Java 21, Docker, Maven), PostgreSQL startup, environment setup, `./mvnw spring-boot:run`, all four curl requests, representative JSON, schema/state behavior, `./mvnw verify`, and an explicit note that the manual OpenAI smoke test costs API usage and is not part of automated tests.

- [ ] **Step 2: Validate configuration and documentation commands**

Run: `docker compose config`

Expected: valid Compose configuration with the PostgreSQL service and no unresolved required variable.

Run: `./mvnw verify`

Expected: BUILD SUCCESS.

- [ ] **Step 3: Start the local database and verify application startup**

Run: `docker compose up -d postgres`

Run with a real key only when available: `OPENAI_API_KEY=<user-provided-key> ./mvnw spring-boot:run`

Expected: Flyway applies V1, Hibernate schema validation passes, the storage directory initializes, and the server listens without logging credentials. If no key is available, rely on the already-passing context/integration suite and report the manual provider smoke test as not run.

- [ ] **Step 4: Perform the optional real-provider smoke test when inputs exist**

Run only with a user-provided small MP3 and API key:

```bash
curl -i -F "file=@/absolute/path/to/sample.mp3;type=audio/mpeg" \
  http://localhost:8080/api/v1/audio
```

Expected: `201 Created`, a UUID, `COMPLETED`, positive duration, detected language when supplied by the provider, and at least one timestamped segment retrievable through the transcript endpoint.

- [ ] **Step 5: Inspect the final diff and commit documentation**

Run: `git diff --check && git status --short`

Expected: no whitespace errors and only intended documentation/configuration changes.

```bash
git add .env.example README.md src/main/resources/application.yml
git commit -m "docs: add local transcription service guide"
```

- [ ] **Step 6: Final verification evidence**

Run: `./mvnw verify`

Run: `git status --short`

Expected: BUILD SUCCESS and a clean working tree. Record whether the optional real-provider smoke test was run, skipped for missing credentials/audio, or failed for an external reason.
