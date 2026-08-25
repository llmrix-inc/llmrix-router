# LLMRix Router API

This document describes the HTTP API exposed by LLMRix Router. The API follows the OpenAI-style protocol while keeping routing, provider, and credential details inside the application.

## Protocol Responses

### Common Rules

- JSON endpoints return a `2xx` status on success.
- `model` is the configured Router route name, such as `general`, `vision`, or `multimodal`; it is not a provider model ID.
- Streaming chat returns `text/event-stream` and a sequence of Server-Sent Events (SSE).
- Speech and video content endpoints return binary data instead of a JSON wrapper.
- Clients should treat optional fields such as `usage`, `tool_calls`, and `error.code` as nullable or absent.

### Common Success Envelope

Chat-style responses contain the following fields:

```json
{
  "id": "chatcmpl-...",
  "object": "chat.completion",
  "created": 1730000000,
  "model": "general",
  "choices": [],
  "usage": {
    "prompt_tokens": 18,
    "completion_tokens": 12,
    "total_tokens": 30
  }
}
```

The `model` field identifies the Router route used by the request. Provider names and upstream response bodies are not part of the public response contract.

### Error Envelope

Controller-level errors use this OpenAI-style structure:

```json
{
  "error": {
    "message": "model service is temporarily unavailable",
    "type": "server_error",
    "param": null,
    "code": null
  }
}
```

Authentication failures rejected by the HTTP filter may use the compact form below because the request did not reach a controller:

```json
{
  "error": {
    "message": "invalid API key",
    "type": "authentication_error"
  }
}
```

### HTTP Statuses

| Status | `type` | `code` | Meaning |
|---:|---|---|---|
| `400` | `invalid_request_error` | `null` or a validation code | Invalid request body, parameter, or content format. |
| `401` | `authentication_error` | `null` | Missing or invalid Router Bearer key. |
| `402` | `billing_error` | `account_capacity_required` | The model service requires account capacity before the request can run. |
| `403` | `permission_error` | `null` | The request is not permitted for the selected model service. |
| `404` | `invalid_request_error` | `model_not_found` or `null` | The route or requested model resource does not exist. |
| `429` | `rate_limit_error` | `rate_limit_exceeded` or `null` | Router quota, concurrency, or model-service rate limit was reached. |
| `500` | `server_error` | `null` | Unclassified application execution failure. |
| `503` | `server_error` | `null` | No model satisfies the request, or the model service is temporarily unavailable. |

The application preserves meaningful HTTP status codes and generates application-level messages. Third-party names, credentials, and raw upstream response bodies are never included in the public error message. Clients should branch on the HTTP status and `error.type` / `error.code`, not on complete message text.

## Setup

Enable the HTTP interface and configure the application Bearer key:

```yaml
llmrix:
  model:
    router:
      http:
        enabled: true
        auth:
          mode: api-key
          bootstrap-key: ${LLMRIX_MODEL_ROUTER_API_KEY}
```

Set request variables:

```bash
export BASE_URL=http://127.0.0.1:8080
export API_KEY=your-llmrix-http-key
```

`API_KEY` is the key for the Router HTTP interface. It is not a provider credential.

## Models

### List Routes

```bash
curl --location "${BASE_URL}/v1/models" \
  --header "Authorization: Bearer ${API_KEY}"
```

Successful response:

```json
{
  "object": "list",
  "data": [
    {"id": "general", "object": "model", "owned_by": "llmrix.model.router"},
    {"id": "vision", "object": "model", "owned_by": "llmrix.model.router"}
  ]
}
```

## Chat

### Synchronous Completion

```bash
curl --location "${BASE_URL}/v1/chat/completions" \
  --header "Authorization: Bearer ${API_KEY}" \
  --header 'Content-Type: application/json' \
  --data '{
    "model": "general",
    "messages": [
      {"role": "user", "content": "Introduce LLMRix Router in three sentences."}
    ],
    "temperature": 0
  }'
```

Successful response:

```json
{
  "id": "chatcmpl-...",
  "object": "chat.completion",
  "created": 1730000000,
  "model": "general",
  "choices": [
    {
      "index": 0,
      "message": {"role": "assistant", "content": "This is the model response."},
      "finish_reason": "stop"
    }
  ],
  "usage": {"prompt_tokens": 18, "completion_tokens": 12, "total_tokens": 30}
}
```

When tools are used, `message.tool_calls` is returned and `message.content` may be `null`.

### Streaming Completion

```bash
curl --no-buffer --location "${BASE_URL}/v1/chat/completions" \
  --header "Authorization: Bearer ${API_KEY}" \
  --header 'Content-Type: application/json' \
  --data '{
    "model": "general",
    "messages": [
      {"role": "user", "content": "Explain model routing in several points."}
    ],
    "stream": true
  }'
```

Successful response (`text/event-stream`):

```text
data: {"id":"chatcmpl-...","object":"chat.completion.chunk","model":"general","choices":[{"index":0,"delta":{"content":"Model"},"finish_reason":""}]}
data: {"id":"chatcmpl-...","object":"chat.completion.chunk","model":"general","choices":[{"index":0,"delta":{"content":" routing"},"finish_reason":""}]}
data: {"id":"chatcmpl-...","object":"chat.completion.chunk","model":"general","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}
data: [DONE]
```

### Responses API

```bash
curl --location "${BASE_URL}/v1/responses" \
  --header "Authorization: Bearer ${API_KEY}" \
  --header 'Content-Type: application/json' \
  --data '{
    "model": "general",
    "input": "Summarize the main capabilities of the model router."
  }'
```

Successful response:

```json
{
  "id": "resp_...",
  "object": "response",
  "created_at": 1730000000,
  "status": "completed",
  "model": "general",
  "output": [
    {
      "id": "msg_...",
      "type": "message",
      "role": "assistant",
      "status": "completed",
      "content": [
        {"type": "output_text", "text": "This is the model response.", "annotations": []}
      ]
    }
  ],
  "usage": {"input_tokens": 18, "output_tokens": 12, "total_tokens": 30}
}
```

## Multimodal Understanding

All image, video, and file understanding requests return the same successful `chat.completion` structure shown above.

### Image Understanding

The image model must declare the `vision` capability. The URL can be replaced with a `data:image/png;base64,...` Data URL.

```bash
curl --location "${BASE_URL}/v1/chat/completions" \
  --header "Authorization: Bearer ${API_KEY}" \
  --header 'Content-Type: application/json' \
  --data '{
    "model": "vision",
    "messages": [{
      "role": "user",
      "content": [
        {"type": "text", "text": "Describe the main content of this image."},
        {"type": "image_url", "image_url": {
          "url": "https://example.com/image.png",
          "detail": "high"
        }}
      ]
    }]
  }'
```

### Video Understanding

The video model must declare `video-input`. Video URLs can also be supplied as `data:video/mp4;base64,...` Data URLs.

```bash
curl --location "${BASE_URL}/v1/chat/completions" \
  --header "Authorization: Bearer ${API_KEY}" \
  --header 'Content-Type: application/json' \
  --data '{
    "model": "multimodal",
    "messages": [{
      "role": "user",
      "content": [
        {"type": "text", "text": "Describe the scenes, actions, and events in this video."},
        {"type": "video_url", "video_url": {
          "url": "https://vjs.zencdn.net/v/oceans.mp4"
        }}
      ]
    }],
    "temperature": 0
  }'
```

### Document and PDF Understanding

The selected model must declare `file-input`. A file can be supplied by public URL, Base64 Data URL, or uploaded file ID.

```bash
curl --location "${BASE_URL}/v1/chat/completions" \
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

The file content can instead use `file_data` with a `data:application/pdf;base64,...` value or `file_id`:

```json
{"type":"file","file":{"filename":"report.pdf","file_data":"data:application/pdf;base64,..."}}
```

```json
{"type":"file","file":{"filename":"report.pdf","file_id":"file-123"}}
```

## Embeddings

The selected route must provide embedding capability. `input` may be a string, an array of strings, or token arrays. `encoding_format` supports `float` and `base64`.

```bash
curl --location "${BASE_URL}/v1/embeddings" \
  --header "Authorization: Bearer ${API_KEY}" \
  --header 'Content-Type: application/json' \
  --data '{
    "model": "embedding",
    "input": ["First text", "Second text"],
    "encoding_format": "float"
  }'
```

Successful response:

```json
{
  "object": "list",
  "data": [
    {"object": "embedding", "embedding": [0.0123, -0.0456], "index": 0},
    {"object": "embedding", "embedding": [0.0789, 0.0012], "index": 1}
  ],
  "model": "embedding",
  "usage": {"prompt_tokens": 10, "total_tokens": 10}
}
```

With `encoding_format=base64`, each `embedding` value is a Base64 string instead of a float array.

## Audio

Chat audio understanding uses the `input_audio` content part. The selected route must declare `audio-input`; this is separate from `file-input`, `audio-transcription`, and `text-to-speech`.

### Audio Understanding

`input_audio.data` is Base64 audio data and `format` identifies the audio format.

```bash
AUDIO_BASE64=$(base64 < ./audio.mp3 | tr -d '\n')

curl --location "${BASE_URL}/v1/chat/completions" \
  --header "Authorization: Bearer ${API_KEY}" \
  --header 'Content-Type: application/json' \
  --data "{
    \"model\": \"multimodal\",
    \"messages\": [{
      \"role\": \"user\",
      \"content\": [
        {\"type\": \"text\", \"text\": \"Transcribe and summarize this audio.\"},
        {\"type\": \"input_audio\", \"input_audio\": {
          \"data\": \"${AUDIO_BASE64}\",
          \"format\": \"mp3\"
        }}
      ]
    }]
  }"
```

Successful response: the same `chat.completion` structure as synchronous text chat.

### Transcription

```bash
curl --location "${BASE_URL}/v1/audio/transcriptions?model=audio" \
  --header "Authorization: Bearer ${API_KEY}" \
  --form 'file=@./audio.mp3' \
  --form 'language=en' \
  --form 'response_format=json'
```

Successful JSON response:

```json
{"text":"This is the transcription result."}
```

### Translation

```bash
curl --location "${BASE_URL}/v1/audio/translations?model=audio" \
  --header "Authorization: Bearer ${API_KEY}" \
  --form 'file=@./audio.mp3' \
  --form 'response_format=json'
```

Successful JSON response:

```json
{"text":"This is the translated result."}
```

### Speech

```bash
curl --location "${BASE_URL}/v1/audio/speech" \
  --header "Authorization: Bearer ${API_KEY}" \
  --header 'Content-Type: application/json' \
  --data '{
    "model": "audio",
    "input": "This is generated speech.",
    "voice": "alloy",
    "response_format": "mp3"
  }' \
  --output speech.mp3
```

Successful response: HTTP `200` with binary audio data and an audio `Content-Type`, commonly `audio/mpeg` or `audio/wav`.

## Images

### Generation

```bash
curl --location "${BASE_URL}/v1/images/generations" \
  --header "Authorization: Bearer ${API_KEY}" \
  --header 'Content-Type: application/json' \
  --data '{
    "model": "image",
    "prompt": "A realistic modern city after rain",
    "size": "1024x1024",
    "n": 1
  }'
```

Successful response:

```json
{
  "created": 1730000000,
  "data": [
    {
      "url": "https://example.com/generated-image.png",
      "revised_prompt": "A realistic modern city after rain"
    }
  ]
}
```

When Base64 image output is used, each item contains `b64_json` instead of `url`.

### Edit

```bash
curl --location "${BASE_URL}/v1/images/edits" \
  --header "Authorization: Bearer ${API_KEY}" \
  --form 'model=image' \
  --form 'prompt=Change the sky to sunset colors' \
  --form 'image=@./input.png'
```

The successful edit response has the same `created` and `data` structure as image generation.

## Videos

Video generation requires a route whose model declares `video-generation`. It is separate from video understanding.

### Create a Video Task

```bash
curl --location "${BASE_URL}/v1/videos" \
  --header "Authorization: Bearer ${API_KEY}" \
  --header 'Content-Type: application/json' \
  --data '{
    "model": "video",
    "prompt": "A calm ocean at sunset, with a slow forward camera movement",
    "seconds": "8",
    "size": "1280x720"
  }'
```

Successful response:

```json
{
  "id": "video_...",
  "object": "video",
  "status": "queued",
  "model": "video",
  "created_at": 1730000000,
  "progress": 0
}
```

### Retrieve a Video Task

```bash
curl --location "${BASE_URL}/v1/videos/{video_id}?model=video" \
  --header "Authorization: Bearer ${API_KEY}"
```

The response uses the same task structure. `status`, `progress`, `completed_at`, and `error` change as processing advances.

### Download Video Content

```bash
curl --location "${BASE_URL}/v1/videos/{video_id}/content?model=video" \
  --header "Authorization: Bearer ${API_KEY}" \
  --output output.mp4
```

Successful response: HTTP `200` with video binary data, commonly `Content-Type: video/mp4`.

### Delete a Video Task

```bash
curl --request DELETE "${BASE_URL}/v1/videos/{video_id}?model=video" \
  --header "Authorization: Bearer ${API_KEY}"
```

### Remix a Video Task

```bash
curl --location "${BASE_URL}/v1/videos/{video_id}/remix?model=video" \
  --header "Authorization: Bearer ${API_KEY}" \
  --header 'Content-Type: application/json' \
  --data '{
    "prompt": "Make the camera movement slower and keep the scene realistic."
  }'
```

Successful response: a new video task object with the same structure as video creation.
