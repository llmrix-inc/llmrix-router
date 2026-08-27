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
  <version>1.0.2</version>
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

The server example provides one complete configuration template in
`application.yml.example`. It lists the full Router property surface, including route quota,
model limits and pricing, provider options, execution policy, observability, HTTP authentication,
and local or Redis state. Copy it to `application.yml` and provide the referenced environment
variables before starting the application.

```yaml
llmrix:
  model:
    router:
      default-route: general
      routes:
        general:
          strategy: balanced
          quota:
            requests-per-minute: 600
            tokens-per-minute: 100000
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
            - integration: openrouter
              model: nvidia/nemotron-3-embed-1b:free
        rerank:
          strategy: balanced
          models:
            - integration: openrouter
              model: nvidia/llama-nemotron-rerank-vl-1b-v2:free
        audio:
          strategy: priority
          models:
            - integration: openai
              model: gpt-4o-mini-transcribe
            - integration: openai
              model: tts-1
        image:
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
        local:
          max-quota-partitions: 10000
          quota-idle-timeout: 2m
      integrations:
        openai:
          provider: openai
          base-url: https://api.openai.com/v1
          api-key: ${OPENAI_API_KEY}
          models:
            - name: gpt-4.1-mini
              operations: [chat]
              features: [tools]
              traits: [code]
            - name: gpt-4.1
              operations: [chat]
              features: [tools]
              traits: [code]
            - name: text-embedding-3-small
              operations: [embeddings]
            - name: gpt-4o-mini-transcribe
              operations: [audio-transcription, audio-translation]
            - name: tts-1
              operations: [text-to-speech]
            - name: gpt-image-1
              operations: [image-generation, image-edit]
        deepseek:
          provider: deepseek
          base-url: https://api.deepseek.com/v1
          api-key: ${DEEPSEEK_API_KEY}
          models:
            - name: deepseek-chat
              operations: [chat]
              features: [tools]
              traits: [code]
            - name: deepseek-reasoner
              operations: [chat]
              traits: [code, reasoning]
        openrouter:
          provider: openrouter
          base-url: https://openrouter.ai/api/v1
          api-key: ${OPENROUTER_API_KEY}
          models:
            - name: anthropic/claude-sonnet-4
              operations: [chat]
              features: [tools]
            - name: nvidia/nemotron-3-embed-1b:free
              operations: [embeddings]
            - name: nvidia/llama-nemotron-rerank-vl-1b-v2:free
              operations: [rerank]
```

`base-url` is configured once per integration and is shared by all models under that integration.
It may point to a proxy or private gateway. OpenAI, DeepSeek, OpenRouter, and Ollama are built in; additional
providers implement the `ModelProvider` SPI.

Each integration contains a `models` list with explicit `name` and operation declarations. Routes
refer to models with explicit `integration` and `model` fields. `routes.<id>.models` is the complete
load-balancing pool; there is no separate fallback list.

Use `operations` for callable endpoints such as `chat`, `embeddings`, and `rerank`. Use `features`
for protocol behavior (`streaming`, `tools`, `structured-output`, and `prompt-cache`),
`input-modalities` for non-text chat input (`vision`, `video`, `audio`, and `file`), and `traits`
for model attributes (`code`, `reasoning`, and `long-context`). These sets are independent and are
validated separately against the provider adapter where applicable. Invalid declarations fail during
startup instead of being silently ignored.

Runtime adapter capabilities are exposed through `ModelTarget.providerCapabilities()` for provider-aware
validation. The `cache-aware` strategy keeps
requests with the same prompt-cache key on the same eligible target.

Routing hints are private to the Client-to-Router hop. Provider integrations do not receive the
`X-LLMRix-Routing-Hints` header by default. For a legacy upstream gateway that explicitly requires
this internal header, enable the compatibility switch on that integration:

```yaml
llmrix:
  model:
    router:
      integrations:
        openrouter:
          forward-routing-hints: true
```

`batch` is reserved for a future batch operation and is not currently supported by any built-in adapter.

Pricing supports input, output, cached-input, cache-write, and reasoning token rates. Usage settlement,
cost budgets, evaluation, and metrics account for those categories separately.

OpenAI exposes Chat, Embeddings, Audio, Images, and Videos. The shared OpenAI-compatible adapter can
also expose Rerank when the configured endpoint implements `POST /rerank`. Ollama exposes native Chat and Embeddings.
OpenRouter exposes Chat Completions, Embeddings, and Rerank through its unified API; the selected
upstream model must support the requested modality. DeepSeek currently exposes Chat. Native
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

Local quota partitions use a bounded Caffeine registry. `max-quota-partitions` rejects new dynamic
quota keys once the limit is reached; it does not evict active partitions and accidentally reset
their counters. `quota-idle-timeout` must be at least one minute so an idle partition cannot be
recreated inside the default quota window. Use Redis for quota state shared across instances.

### Video Generation Route

Video generation requires a dedicated route and a provider model with the `video-generation` operation:

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
              operations: [video-generation]
```

## Standalone Example

The runnable server example is in `llmrix-model-router-server-examples`:

```bash
mvn -pl :llmrix-model-router-server-examples -am package -DskipTests
java -jar llmrix-model-examples/llmrix-model-router-server-examples/target/llmrix-model-router-server-examples-1.0.2-exec.jar
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

Embedding and Rerank routes can be called with the same Router Bearer key:

```bash
curl --location 'http://127.0.0.1:8080/v1/embeddings' \
  --header "Authorization: Bearer ${API_KEY}" \
  --header 'Content-Type: application/json' \
  --data '{"model":"embeddings","input":"Text to embed"}'

curl --location 'http://127.0.0.1:8080/v1/rerank' \
  --header "Authorization: Bearer ${API_KEY}" \
  --header 'Content-Type: application/json' \
  --data '{"model":"rerank","query":"refund policy","documents":["Refunds are available within 30 days.","Contact support by email."],"top_n":1}'
```

## HTTP API

See [api.md](api.md) for protocol structures, status codes, successful responses, and error formats.

| Endpoint | Support |
|---|---|
| `POST /v1/chat/completions` | Synchronous and SSE streaming chat completions. |
| `POST /v1/responses` | Core Responses API subset, with JSON and SSE streaming responses. |
| `POST /v1/embeddings` | Text or token-array embeddings with `float` and `base64` encoding. |
| `POST /v1/rerank` | Cohere/Jina-compatible query/document reranking. |
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
| `GET /v1/models` | Available chat route identifiers. Operation-only routes are selected by their endpoint. |

The server example includes free OpenRouter targets for both retrieval operations:

- Embeddings: `liquid/lfm-2.5-embedding-350m:free`, `nvidia/nemotron-3-embed-1b:free`, and
  `nvidia/llama-nemotron-embed-vl-1b-v2:free`.
- Rerank: `nvidia/llama-nemotron-rerank-vl-1b-v2:free` and `qwen/qwen3-reranker-8b`.

The `model` value sent to the Router HTTP API is the route name (`embeddings` or `rerank`), not the
upstream OpenRouter model ID. The upstream model IDs are selected inside the route configuration.

Authentication defaults to `api-key`; use `mode: none` only in a trusted network. The starter accepts
opaque Bearer keys through `ApiKeyVerifier`, propagates or generates `X-Request-Id`, and emits
OpenAI-shaped errors. It does not issue keys, manage tenants, or store secrets.

### Video Task

The default standalone example does not enable a video route. Configure a `video` route as shown in
the Video Generation Route section before using the following commands.

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
`input-modalities: [file]`:

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
Availability depends on the selected provider and model file input protocol.
