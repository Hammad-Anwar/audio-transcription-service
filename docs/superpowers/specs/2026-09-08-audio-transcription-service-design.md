# Audio Transcription Service Design

## Objective

Build a standalone Spring Boot backend that accepts one MP3 upload, stores the original audio on the local filesystem, transcribes it through Spring AI and OpenAI, persists segment-level timestamps in PostgreSQL, and exposes read APIs for metadata and transcript lookup.

The MVP has no frontend, Python service, message broker, vector database, transcript file, or audio streaming endpoint.

## Technology Baseline

- Java 21
- Maven
- Spring Boot 4.1.1
- Spring AI 2.0.1
- Spring Web MVC
- Spring Data JPA
- Flyway
- PostgreSQL
- Docker Compose for PostgreSQL only
- JUnit and Spring Boot Test
- Testcontainers PostgreSQL for database integration tests

Spring AI 2.0.1 supports Spring Boot 4.1.x. The OpenAI integration uses `spring-ai-starter-model-openai`, `TranscriptionModel`, and provider-specific `OpenAiAudioTranscriptionOptions` only inside the transcription adapter.

## Scope

### Included

- `POST /api/v1/audio`
- `GET /api/v1/audio/{audioId}`
- `GET /api/v1/audio/{audioId}/transcript`
- `GET /api/v1/audio/{audioId}/transcript/at?timeMs={value}`
- MP3 validation and configurable upload size
- UUID-based local file storage
- Synchronous OpenAI transcription
- Segment timestamps normalized to milliseconds
- PostgreSQL persistence and Flyway schema ownership
- Consistent REST error responses
- Unit, MVC, and PostgreSQL integration tests
- Local development instructions and example requests

### Excluded

- Background jobs, queues, retries, or polling endpoints
- Word-level timestamps, diarization, or translation
- Audio streaming and HTTP Range support
- User accounts and authorization
- Cloud object storage
- Containerizing the Spring Boot application
- Real OpenAI calls in automated tests

## Architecture

The application uses a small layered architecture with explicit external boundaries:

```text
HTTP -> AudioController -> AudioService
                            |      |
                            |      +-> AudioStorageService -> local MP3
                            |
                            +-> TranscriptionService -> Spring AI -> OpenAI
                            |
                            +-> AudioPersistenceService -> repositories -> PostgreSQL
```

`AudioController` handles HTTP mapping only. `AudioService` coordinates the use case without owning filesystem, provider, or transaction details. `AudioStorageService` owns path safety and file operations. `TranscriptionService` is the only class coupled to Spring AI/OpenAI response types. `AudioPersistenceService` owns short database transactions around state changes.

The application uses constructor injection and no Lombok. REST responses and internal transcription results are Java records. JPA entities never cross the controller boundary.

## Upload Workflow

The upload endpoint processes synchronously:

1. Validate the multipart file.
2. Generate the audio UUID.
3. Store the MP3 as `{audioId}.mp3` beneath the configured storage root.
4. Insert an `audio_files` row with `PROCESSING` status in a short transaction.
5. Call OpenAI through Spring AI outside any database transaction.
6. Normalize language, duration, and segments into provider-independent Java records.
7. In one short transaction, insert all segments and update the audio row to `COMPLETED`.
8. Return `201 Created` with the audio resource summary and a `Location` header for `/api/v1/audio/{audioId}`.

`UPLOADED` is omitted because synchronous processing would transition through it immediately and require an extra database write without adding observable behavior.

## Failure and Consistency Rules

- If filesystem storage fails, no database row is created.
- If the initial database insert fails after storage succeeds, the just-created file is deleted as compensation.
- Once the database row exists, the stored file and row are preserved even if transcription fails.
- A provider error, unreadable/corrupt audio response, or response without timestamped segments updates the row to `FAILED` in a new transaction.
- The upload request then returns a provider-facing error status with a safe error body containing `audioId`, allowing the failed resource to be retrieved later.
- The database stores a bounded, sanitized diagnostic message; stack traces, credentials, and complete transcripts are never exposed or persisted as errors.
- Saving transcript segments and marking the audio `COMPLETED` occur atomically.
- No transaction remains open during filesystem I/O or the OpenAI request.

If updating `FAILED` itself encounters a database outage, the original transcription exception remains the primary failure and the status-update failure is logged with the audio ID.

## Spring AI Transcription Contract

The transcription adapter constructs an `AudioTranscriptionPrompt` for the stored resource and calls the configured `TranscriptionModel`. It supplies OpenAI options equivalent to:

- model: `whisper-1`
- response format: `verbose_json`
- timestamp granularity: `segment`
- temperature: `0`

Spring AI requires `verbose_json` for language, duration, and timestamp metadata. Segment timestamps add no provider-documented latency, unlike word timestamps. The adapter reads provider metadata and immediately maps it to:

```java
record TranscriptionResult(
    String language,
    long durationMs,
    List<TranscriptionSegmentResult> segments
) {}

record TranscriptionSegmentResult(
    int index,
    long startTimeMs,
    long endTimeMs,
    String text
) {}
```

Floating-point seconds are converted with `Math.round(seconds * 1000)`. The adapter rejects missing segments, negative timestamps, `end <= start`, blank segment text, and non-monotonic provider ordering. Segment indexes are reassigned sequentially from zero after validating the provider order, avoiding dependence on provider-specific IDs.

## File Validation and Storage

The first release accepts only MP3 files. Validation checks:

- the multipart part exists and is non-empty;
- the original filename is present and ends in `.mp3`, case-insensitively;
- the reported media type is `audio/mpeg`, `audio/mp3`, or `application/octet-stream`;
- the payload does not exceed the configured maximum;
- the initial bytes contain either an ID3 header or an MPEG audio frame sync.

The reported content type is only a hint and is never trusted alone. Deep MP3 decoding is deliberately not added to the MVP; the provider remains the final corruption check.

The configured storage root defaults to `./uploads/audio`. It is normalized once at startup, created if missing, and required to be a directory. Stored paths are resolved from generated UUID filenames only, normalized, and checked to remain beneath that root. Original filenames are retained solely as metadata.

The database stores the generated filename and a storage-root-relative path, not an absolute machine path. REST responses never expose either internal path.

## Persistence Model

Flyway owns the schema through one initial migration because both tables are introduced together in a greenfield project. Hibernate uses `ddl-auto: validate`.

### `audio_files`

- `id UUID PRIMARY KEY`
- `original_filename VARCHAR(255) NOT NULL`
- `stored_filename VARCHAR(255) NOT NULL UNIQUE`
- `file_path TEXT NOT NULL UNIQUE`
- `content_type VARCHAR(100) NOT NULL`
- `file_size BIGINT NOT NULL CHECK (file_size > 0)`
- `duration_ms BIGINT NULL CHECK (duration_ms > 0)`
- `language VARCHAR(20) NULL`
- `status VARCHAR(30) NOT NULL`
- `error_message TEXT NULL`
- `created_at TIMESTAMP WITH TIME ZONE NOT NULL`
- `updated_at TIMESTAMP WITH TIME ZONE NOT NULL`

Statuses are `PROCESSING`, `COMPLETED`, and `FAILED`, persisted by name. `Instant` represents timestamps in Java. Application lifecycle callbacks set creation and update timestamps; database defaults provide defensive values.

### `transcript_segments`

- `id BIGSERIAL PRIMARY KEY`
- `audio_file_id UUID NOT NULL REFERENCES audio_files(id) ON DELETE CASCADE`
- `segment_index INTEGER NOT NULL CHECK (segment_index >= 0)`
- `start_time_ms BIGINT NOT NULL CHECK (start_time_ms >= 0)`
- `end_time_ms BIGINT NOT NULL CHECK (end_time_ms > start_time_ms)`
- `text TEXT NOT NULL`
- unique constraint on `(audio_file_id, segment_index)`
- lookup index on `(audio_file_id, segment_index)`
- timestamp lookup index on `(audio_file_id, start_time_ms, end_time_ms)`

The JPA relationship is unidirectional from `TranscriptSegment` to `AudioFile`. `AudioFile` has no segments collection, avoiding accidental eager loading and JSON recursion.

## REST Contract

### Upload

`POST /api/v1/audio`, multipart field `file`.

Success returns `201 Created`:

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "filename": "lecture.mp3",
  "status": "COMPLETED",
  "durationMs": 15600,
  "language": "en",
  "segmentCount": 3
}
```

Because processing is synchronous, successful creation always returns `COMPLETED`. Provider failure returns `502 Bad Gateway`; timeout or provider unavailability returns `503 Service Unavailable` when distinguishable. Both include the created audio ID.

### Metadata

`GET /api/v1/audio/{audioId}` returns the public filename, content type, size, duration, language, status, safe failure message when applicable, and timestamps. Unknown UUIDs return `404 Not Found`.

### Transcript

`GET /api/v1/audio/{audioId}/transcript` returns metadata and segments ordered by `segment_index ASC`.

- `COMPLETED`: `200 OK` with segments.
- `PROCESSING`: `409 Conflict` with `TRANSCRIPTION_NOT_READY`.
- `FAILED`: `409 Conflict` with `TRANSCRIPTION_FAILED` and the safe stored message.
- unknown audio: `404 Not Found`.

### Timestamp Lookup

`GET /api/v1/audio/{audioId}/transcript/at?timeMs={value}` requires a non-negative integer. Segment intervals are half-open: `start_time_ms <= timeMs AND end_time_ms > timeMs`. This prevents adjacent segments from both matching at a shared boundary. No matching segment returns `404 Not Found` with `SEGMENT_NOT_FOUND`.

## Error Representation

All errors use one application record:

```json
{
  "status": 400,
  "error": "INVALID_AUDIO_FILE",
  "message": "Only MP3 files are currently supported.",
  "audioId": null,
  "timestamp": "2026-09-08T12:00:00Z"
}
```

`audioId` is nullable and is populated when a record was created before failure. Validation maps to `400`, multipart size violations to `413`, missing resources to `404`, invalid resource state to `409`, storage/database failures to `500`, and upstream transcription failures to `502` or `503`.

## Configuration

Environment-backed properties include:

- `DATABASE_URL`, default `jdbc:postgresql://localhost:5432/audio_transcription`
- `DATABASE_USERNAME`, default `audio_user`
- `DATABASE_PASSWORD`, default `audio_password` for local development only
- `OPENAI_API_KEY`, with no default
- `AUDIO_STORAGE_DIRECTORY`, default `./uploads/audio`
- `AUDIO_MAX_FILE_SIZE`, default `25MB`

Spring multipart request and file limits use the same maximum. The OpenAI key must never be committed. Docker Compose reads local PostgreSQL defaults while permitting environment overrides. `.env.example` documents names without secrets.

## Testing Strategy

Development follows red-green-refactor behavior by behavior.

- Plain unit tests cover file validation, safe path generation, compensation behavior, state transitions, timestamp normalization, and malformed provider metadata.
- MVC tests cover multipart upload and each read contract through the real controller and advice while replacing only the external transcription boundary.
- Testcontainers PostgreSQL tests run Flyway and verify schema/entity agreement, segment ordering, half-open timestamp lookup, unique constraints, and cascade deletion.
- A focused Spring context test verifies configuration binding and application startup with transcription auto-configuration disabled or replaced for tests.
- Automated tests never call OpenAI. README instructions provide an opt-in manual smoke test using a small MP3 and a real `OPENAI_API_KEY`.

Temporary test storage uses per-test directories and test-owned cleanup utilities.

## Logging and Observability

Logs include the audio ID and state transitions: upload received, file stored, transcription started, transcription completed with segment count and duration, and failure category. Logs exclude API keys, credentials, raw audio, full transcript content, and stack traces in HTTP responses.

## Delivery Sequence

1. Bootstrap Maven, configuration, PostgreSQL Compose, and a context test.
2. Add Flyway schema, entities, repositories, and PostgreSQL integration tests.
3. Add MP3 validation and local storage using test-first development.
4. Add the Spring AI adapter and provider-response normalization using test fixtures.
5. Add transactional persistence operations and the synchronous orchestration workflow.
6. Add REST endpoints and error handling.
7. Run the full automated suite and add README operational instructions.
8. Perform the opt-in real-provider smoke test only when credentials and an audio fixture are available.

## Definition of Done

The MVP is complete when a valid MP3 upload produces a stored UUID-named file, one `COMPLETED` audio row, ordered timestamped segment rows, and a `201` response; all three read endpoints return the specified representations; provider failure preserves the file and a `FAILED` row; Flyway and Hibernate validation succeed against PostgreSQL; and the automated test suite passes without real OpenAI access.
