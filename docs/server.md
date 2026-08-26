# LLMRix Router Server

This guide covers deploying the LLMRix Router as a Spring Boot service and calling its
OpenAI-compatible HTTP API.

## Requirements

- Java 17 or later; Java 21 is recommended.
- Maven 3.9 or later.
- Redis is optional and required only for shared multi-instance runtime state.

## Spring Boot Dependency

For an existing Spring Boot application, add the Router starter and a web runtime:

```xml
<dependency>
  <groupId>com.llmrix.model</groupId>
  <artifactId>llmrix-model-router-spring-starter</artifactId>
  <version>1.0.1</version>
</dependency>
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-web</artifactId>
</dependency>
```

The starter creates routed model beans, validates model targets at startup, and contributes health
and telemetry integration when the corresponding host libraries are present.

## Configuration

The HTTP API is disabled unless `llmrix.model.router.http.enabled=true`:

```yaml
llmrix:
  model:
    router:
      default-route: general
      routes:
        general:
          strategy: balanced
          models:
            - integration: openai
              model: gpt-4.1-mini
            - integration: deepseek
              model: deepseek-chat
            - integration: openrouter
              model: anthropic/claude-sonnet-4
        embeddings:
          strategy: balanced
          models:
            - integration: openai
              model: text-embedding-3-small
        audio:
          strategy: priority
          models:
            - integration: openai
              model: gpt-4o-mini-transcribe
            - integration: openai
              model: tts-1
        images:
          strategy: priority
          models:
            - integration: openai
              model: gpt-image-1
      execution:
        timeout: 30s
        max-retries: 1
      http:
        enabled: true
        auth:
          mode: api-key
          bootstrap-key: ${LLMRIX_MODEL_ROUTER_API_KEY}
      state:
        mode: local # use redis for shared multi-instance limits and health
      integrations:
        openai:
          provider: openai
          base-url: https://api.openai.com/v1
          api-key: ${OPENAI_API_KEY}
          models:
            - name: gpt-4.1-mini
              capabilities: [chat, tools, code]
            - name: gpt-4.1
              capabilities: [chat, tools, code]
            - name: text-embedding-3-small
              capabilities: [embeddings]
            - name: gpt-4o-mini-transcribe
              capabilities: [audio-transcription, audio-translation]
            - name: tts-1
              capabilities: [text-to-speech]
            - name: gpt-image-1
              capabilities: [image-generation, image-edit]
        deepseek:
          provider: deepseek
          base-url: https://api.deepseek.com/v1
          api-key: ${DEEPSEEK_API_KEY}
          models:
            - name: deepseek-chat
              capabilities: [chat, tools, code]
            - name: deepseek-reasoner
              capabilities: [chat, code, reasoning]
        openrouter:
          provider: openrouter
          base-url: https://openrouter.ai/api/v1
          api-key: ${OPENROUTER_API_KEY}
          models:
            - name: anthropic/claude-sonnet-4
              capabilities: [chat, tools]
```

`base-url` is configured once per integration and is shared by all models under that integration.
It may point to a proxy or private gateway. OpenAI, DeepSeek, and OpenRouter are built in; additional
providers implement the `ModelProvider` SPI.

Each integration contains a `models` list with explicit `name` and `capabilities` values. Routes
refer to models with explicit `integration` and `model` fields. `routes.<id>.models` is the complete
load-balancing pool; there is no separate fallback list.

Capabilities must match the provider protocol. Models used for image, video, audio, or document
understanding must declare `vision`, `video-input`, `audio-input`, or `file-input` respectively.
Invalid capability declarations fail during startup instead of being silently ignored.

OpenAI exposes Chat, Embeddings, Audio, Images, and Videos. OpenRouter exposes Chat Completions and
the modalities supported by each selected upstream model. DeepSeek currently exposes Chat. Native
transcription, speech, image-generation, and video-generation routes should use a provider that
implements the corresponding native endpoint.

### Shared Redis State

Use Redis when health, quota, and concurrency state must be shared across service instances:

```yaml
llmrix:
  model:
    router:
      state:
        mode: redis
        redis:
          uri: redis://localhost:6379/0
          key-prefix: llmrix:model:router
          lease-ttl: 2m
          quota-window: 1m
```

Redis mode is fail-closed. An invalid configuration or unavailable store does not silently downgrade
global limits to JVM-local limits.

### Video Generation Route

Video generation requires a dedicated route and a provider model with `video-generation` capability:

```yaml
llmrix:
  model:
    router:
      routes:
        video:
          strategy: priority
          models:
            - integration: openai
              model: sora
      integrations:
        openai:
          provider: openai
          base-url: https://api.openai.com/v1
          api-key: ${OPENAI_API_KEY}
          models:
            - name: sora
              capabilities: [video-generation]
```

## Standalone Example

The runnable server example is in `llmrix-model-router-server-examples`:

```bash
mvn -pl :llmrix-model-router-server-examples -am package -DskipTests
java -jar llmrix-model-examples/llmrix-model-router-server-examples/target/llmrix-model-router-server-examples-1.0.1-exec.jar
```

The default port is `8080`. `API_KEY` is the Bearer key for the Router HTTP API and must match
`llmrix.model.router.http.auth.bootstrap-key`; it is not a provider key:

```bash
export API_KEY=your-llmrix-http-key

curl --location 'http://127.0.0.1:8080/v1/models' \
  --header "Authorization: Bearer ${API_KEY}"

curl --location 'http://127.0.0.1:8080/v1/chat/completions' \
  --header "Authorization: Bearer ${API_KEY}" \
  --header 'Content-Type: application/json' \
  --data '{
    "model": "general",
    "messages": [{"role": "user", "content": "Introduce yourself"}],
    "temperature": 0
  }'
```

The request `model` is a route name such as `general`, `reasoning`, or `code`, not an underlying
provider model ID.

## HTTP API

See [api.md](api.md) for protocol structures, status codes, successful responses, and error formats.

| Endpoint | Support |
|---|---|
| `POST /v1/chat/completions` | Synchronous and SSE streaming chat completions. |
| `POST /v1/responses` | Core Responses API subset. |
| `POST /v1/embeddings` | Text or token-array embeddings with `float` and `base64` encoding. |
| `POST /v1/audio/transcriptions` | Multipart audio transcription. |
| `POST /v1/audio/translations` | Multipart audio translation. |
| `POST /v1/audio/speech` | Text-to-speech with a binary audio response. |
| `POST /v1/images/generations` | Image generation. |
| `POST /v1/images/edits` | Multipart image editing. |
| `POST /v1/videos` | Create a video generation task. |
| `GET /v1/videos/{video_id}` | Retrieve video task status. |
| `GET /v1/videos/{video_id}/content` | Download completed video content. |
| `DELETE /v1/videos/{video_id}` | Delete a video task. |
| `POST /v1/videos/{video_id}/remix` | Create a remix task. |
| `GET /v1/models` | Available routed model and route identifiers. |

Authentication defaults to `api-key`; use `mode: none` only in a trusted network. The starter accepts
opaque Bearer keys through `ApiKeyVerifier`, propagates or generates `X-Request-Id`, and emits
OpenAI-shaped errors. It does not issue keys, manage tenants, or store secrets.

### Video Task

```bash
curl --location 'http://127.0.0.1:8080/v1/videos' \
  --header "Authorization: Bearer ${API_KEY}" \
  --header 'Content-Type: application/json' \
  --data '{
    "model": "video",
    "prompt": "A calm ocean scene at sunset",
    "seconds": "8",
    "size": "1280x720"
  }'
```

Use the returned `id` to retrieve or download the task:

```bash
curl --location 'http://127.0.0.1:8080/v1/videos/{video_id}?model=video' \
  --header "Authorization: Bearer ${API_KEY}"

curl --location 'http://127.0.0.1:8080/v1/videos/{video_id}/content?model=video' \
  --header "Authorization: Bearer ${API_KEY}" \
  --output output.mp4
```

### Video Understanding

Video understanding uses a Chat Completions `video_url` content part with a configured multimodal
route:

```bash
curl --location 'http://127.0.0.1:8080/v1/chat/completions' \
  --header "Authorization: Bearer ${API_KEY}" \
  --header 'Content-Type: application/json' \
  --data '{
    "model": "multimodal",
    "messages": [{
      "role": "user",
      "content": [
        {"type": "text", "text": "Analyze this video and describe its scenes."},
        {"type": "video_url", "video_url": {
          "url": "https://vjs.zencdn.net/v/oceans.mp4"
        }}
      ]
    }]
  }'
```

`video_url` accepts a public URL or a `data:video/mp4;base64,...` URL. URL support depends on the
selected upstream model. `/v1/videos` remains the video-generation lifecycle API.

### Document Understanding

Document and PDF understanding uses a Chat Completions `file` content part. The route must declare
`file-input`:

```bash
curl --location 'http://127.0.0.1:8080/v1/chat/completions' \
  --header "Authorization: Bearer ${API_KEY}" \
  --header 'Content-Type: application/json' \
  --data '{
    "model": "documents",
    "messages": [{
      "role": "user",
      "content": [
        {"type": "text", "text": "Summarize the main points of this PDF."},
        {"type": "file", "file": {
          "filename": "report.pdf",
          "file_url": "https://example.com/report.pdf"
        }}
      ]
    }]
  }'
```

Use `file_url` for a public file, `file_data` with a Base64 Data URL, or an uploaded `file_id`.
Availability depends on the selected provider and model file-input protocol.
