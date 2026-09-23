# Audio Transcription Service

A Spring Boot API that accepts MP3 uploads, stores the original file locally, transcribes it with a local whisper.cpp server, and saves segment-level timestamps in PostgreSQL. Redis optionally caches completed metadata and transcripts.

The MVP is intentionally synchronous: an upload request completes only after transcription and database persistence finish. A Whisper failure leaves the audio file in place and records a `FAILED` row so the result is observable and recoverable.

## Architecture

The request flow is:

1. Validate the extension, content type, size, and MP3 header.
2. Atomically store the file as `<uuid>.mp3` under the configured directory.
3. Insert an `audio_files` row with status `PROCESSING`.
4. Ask the local Whisper server for `verbose_json` with segment timestamps.
5. Atomically persist all `transcript_segments` and change the audio status to `COMPLETED`.

The code is separated into focused `controller`, `dto`, `model`, `repository`,
`service`, `storage`, `whisper`, `config`, and `error` packages. PostgreSQL is
always the source of truth. Redis caches only completed audio metadata and
completed transcripts; it never stores audio bytes, processing/failed records,
lists, or timestamp lookup results.

Flyway owns the schema. Transcript timestamp lookup uses half-open ranges (`start <= time < end`), so a timestamp exactly on a boundary selects the next segment.

## Prerequisites

- Java 21
- Docker with Docker Compose
- A running whisper.cpp server

The Maven wrapper is included; a separate Maven installation is not required.

## Run locally

Create your local environment file:

```bash
cp .env.example .env
set -a
source .env
set +a
```

Start PostgreSQL and Redis:

```bash
docker compose up -d postgres redis
```

Run the service:

```bash
./mvnw spring-boot:run
```

The example uses application port `8081` and expects whisper.cpp on `8082`.

### Local whisper.cpp server

Download the multilingual `base` model once:

```bash
mkdir -p local-whisper-models

docker run --rm \
  -v "$PWD/local-whisper-models:/models" \
  ghcr.io/ggml-org/whisper.cpp:main \
  "./models/download-ggml-model.sh base /models"
```

Start the server:

```bash
docker run --rm \
  -p 8082:8082 \
  -v "$PWD/local-whisper-models:/models" \
  ghcr.io/ggml-org/whisper.cpp:main \
  "whisper-server --host 0.0.0.0 --port 8082 --convert --language auto -m /models/ggml-base.bin"
```

Then run the application with:

```bash
LOCAL_WHISPER_URL=http://localhost:8082 \
SERVER_PORT=8081 \
./mvnw spring-boot:run
```

Local requests use a 2-second connection timeout and a 10-minute inference
timeout by default. Override them with `LOCAL_WHISPER_CONNECT_TIMEOUT` and
`LOCAL_WHISPER_READ_TIMEOUT` when processing longer recordings or using a
slower machine.

On startup, Flyway creates the tables, Hibernate validates the schema, and the audio storage directory is created when it is first needed.

### Redis cache

Redis is a disposable, fail-open cache. If it is stopped or unavailable, read
requests continue against PostgreSQL and the cache failure is logged. Completed
metadata uses `audioMetadata::<audioId>` keys and completed transcripts use
`transcripts::<audioId>` keys. Both expire after 30 minutes by default.

Configure it with `REDIS_HOST`, `REDIS_PORT`, and `REDIS_CACHE_TTL` (for example
`30m` or `1h`). No Redis persistence volume is used because every cached value
can be rebuilt from PostgreSQL.

## API

Upload an MP3:

```bash
curl -i \
  -F "file=@/absolute/path/to/sample.mp3;type=audio/mpeg" \
  http://localhost:8081/api/v1/audio
```

A successful upload returns `201 Created`, a `Location` header, and a body like:

```json
{
  "id": "4c68cd27-e4a8-47c8-a91e-8430cc8888ac",
  "filename": "sample.mp3",
  "status": "COMPLETED",
  "durationMs": 10200,
  "language": "en",
  "segmentCount": 2
}
```

Use the returned ID in the read endpoints:

```bash
curl http://localhost:8081/api/v1/audio/4c68cd27-e4a8-47c8-a91e-8430cc8888ac

curl http://localhost:8081/api/v1/audio/4c68cd27-e4a8-47c8-a91e-8430cc8888ac/transcript

curl "http://localhost:8081/api/v1/audio/4c68cd27-e4a8-47c8-a91e-8430cc8888ac/transcript/at?timeMs=4800"
```

The transcript response contains ordered segments:

```json
{
  "audioId": "4c68cd27-e4a8-47c8-a91e-8430cc8888ac",
  "filename": "sample.mp3",
  "durationMs": 10200,
  "language": "en",
  "segments": [
    {"index": 0, "startTimeMs": 0, "endTimeMs": 4800, "text": "Hello"},
    {"index": 1, "startTimeMs": 4800, "endTimeMs": 10200, "text": "world"}
  ]
}
```

Errors use a consistent JSON envelope with an HTTP status, stable error code, message, optional `audioId`, and timestamp. Invalid files return `400`; missing resources return `404`; unavailable transcript states return `409`; Whisper failures return `502`; and multipart size violations return `413`.

## Persistence and failure behavior

`audio_files` owns each upload and transitions through `PROCESSING`, `COMPLETED`, or `FAILED`. `transcript_segments` has a unique `(audio_file_id, segment_index)` constraint and is deleted automatically with its parent audio record.

If Whisper fails, the upload response contains the generated `audioId`, the MP3 remains stored, and the database row changes to `FAILED` with a bounded diagnostic message. Segment persistence and the `COMPLETED` transition occur in one transaction, preventing partial transcripts.

## Tests

Run the full suite:

```bash
./mvnw verify
```

Repository and workflow integration tests start isolated PostgreSQL 17 and Redis 7 containers. Automated tests mock the Whisper boundary and do not require a running Whisper server or developer-managed Redis instance.

Stop the local services when finished:

```bash
docker compose down
```
