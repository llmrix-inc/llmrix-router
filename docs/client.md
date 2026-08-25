# Orion Client

Orion is the framework-neutral Java client for calling a remote LLMRix Router. It uses Router route
names rather than upstream provider model IDs.

## Maven Dependency

```xml
<dependency>
  <groupId>com.llmrix.model</groupId>
  <artifactId>llmrix-model-orion</artifactId>
  <version>1.0.0</version>
</dependency>
```

The Router must expose HTTP separately with `llmrix.model.router.http.enabled=true`. The client does
not start an HTTP server and provider keys remain on the Router deployment.

## Java Client

Configure a default route for every native operation the application will call:

```java
import com.llmrix.model.orion.client.OrionModelClient;
import com.llmrix.model.orion.client.OrionModelRequestOptions;
import com.llmrix.model.orion.model.RouterModel;
import com.llmrix.model.router.core.api.audio.*;
import com.llmrix.model.router.core.api.chat.*;
import com.llmrix.model.router.core.api.embedding.*;
import com.llmrix.model.router.core.api.image.*;
import com.llmrix.model.router.core.api.video.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

OrionModelClient client = OrionModelClient.builder()
    .baseUrl("https://router.example.com/v1")
    .apiKey(System.getenv("LLMRIX_API_KEY"))
    .defaultModel("general")
    .defaultEmbeddingModel("embedding")
    .defaultAudioModel("audio")
    .defaultImageModel("image")
    .defaultVideoModel("video")
    .build();

ChatResponse response = client.chat(ChatRequest.user("Summarize this incident"));
EmbeddingResponse vectors = client.embed(EmbeddingRequest.text("Index this incident"));
ChatResponse responsesApi = client.responsesModel("general")
    .chat(ChatRequest.user("Use the Responses-compatible route."));
List<RouterModel> routes = client.models();
```

`baseUrl` normally ends in `/v1`. The API key is the key accepted by the Router, not a provider key.

## Typed Models

Use a route-scoped typed model when the route is selected per operation:

```java
ChatModel chat = client.chatModel("general");
EmbeddingModel embeddings = client.embeddingModel("embedding");
AudioModel audio = client.audioModel("audio");
ImageModel images = client.imageModel("image");
VideoModel videos = client.videoModel("video");

ChatResponse answer = chat.chat(ChatRequest.user("Explain the routing policy."));
EmbeddingResponse vector = embeddings.embed(EmbeddingRequest.text("Text to index"));

byte[] audioBytes = Files.readAllBytes(Path.of("meeting.mp3"));
AudioResponse transcript = audio.transcribe(new AudioTextRequest(
    new AudioInput(audioBytes, "meeting.mp3", "audio/mpeg"),
    "en", null, AudioTextRequest.ResponseFormat.JSON, null, List.of(), null));
AudioResponse speech = audio.speech(new SpeechRequest(
    "A generated announcement.", "alloy", "mp3", null, null, null));

ImageResponse generated = images.generate(new ImageRequest(
    "A clean technical illustration", 1, "1024x1024", null, null,
    "url", null, null, null, null, null));

VideoResponse task = videos.create(new VideoRequest(
    "A slow camera move over an ocean at sunset", "8", "1280x720", (String) null, null));
VideoResponse status = videos.retrieve(new VideoLookupRequest(task.id(), null));
VideoContent video = videos.content(new VideoLookupRequest(task.id(), null));
```

`AudioModel` also supports translation. `ImageModel.edit` accepts an `ImageEditRequest` with
multipart image inputs. Video models also support delete and remix operations.

## Multimodal Chat

Chat Completions accepts text, image, video, audio, and file content parts. URLs can be replaced with
Base64 Data URLs; audio content itself is Base64 data:

```java
ChatRequest multimodal = ChatRequest.builder()
    .message(Message.user(
        new TextPart("Describe this image and compare it with the attached report."),
        new ImagePart("https://example.com/diagram.png", "high"),
        new FilePart("https://example.com/report.pdf", "report.pdf")))
    .build();
ChatResponse analysis = client.chatModel("multimodal").chat(multimodal);

String audioBase64 = "<base64-encoded-audio>";
ChatRequest audioAndVideo = ChatRequest.builder()
    .message(Message.user(
        new TextPart("Summarize the audio and describe the video scenes."),
        new AudioPart(audioBase64, "mp3"),
        new VideoPart("https://example.com/oceans.mp4")))
    .build();
ChatResponse multimodalResponse = client.chatModel("multimodal").chat(audioAndVideo);
```

The selected routes must declare `vision`, `video-input`, `audio-input`, or `file-input`. Video
understanding uses Chat Completions. The Responses adapter currently supports text, image, file, and
tool input, but not video or audio input.

For local audio transcription:

```java
byte[] audioBytes = Files.readAllBytes(Path.of("meeting.mp3"));
AudioResponse transcript = client.audioModel("audio").transcribe(new AudioTextRequest(
    new AudioInput(audioBytes, "meeting.mp3", "audio/mpeg"),
    "en", null, AudioTextRequest.ResponseFormat.JSON, null, List.of(), null));
```

## Async and Streaming

```java
CompletionStage<ChatResponse> future = client.chatModel("general")
    .chatAsync(ChatRequest.user("Summarize this asynchronously."));

Flow.Publisher<ChatChunk> chunks = client.chatModel("general")
    .stream(ChatRequest.user("Stream a short explanation."));
```

Remote application errors and HTTP status are preserved on the client's model exception types.

## Request Options

Request IDs and safe custom headers can be supplied for one invocation without changing the cached
model. Authorization is managed by `apiKey` and cannot be overridden:

```java
OrionModelRequestOptions options = OrionModelRequestOptions.builder()
    .requestId("req-123")
    .header("X-Tenant", "acme")
    .build();

ChatResponse responseWithCorrelation = client
    .chatModel("support-route", options)
    .chat(ChatRequest.user("Correlate this request."));
```

Header names and values reject CR/LF characters.

## Spring Boot Client

Add the optional Spring integration instead of constructing the client manually:

```xml
<dependency>
  <groupId>com.llmrix.model</groupId>
  <artifactId>llmrix-model-orion-spring-starter</artifactId>
  <version>1.0.0</version>
</dependency>
```

```yaml
llmrix:
  model:
    orion:
      enabled: true
      base-url: https://router.example.com/v1
      api-key: ${LLMRIX_API_KEY}
      defaults:
        chat: general
        embedding: embedding
        audio: audio
        image: image
        video: video
```

The starter creates `OrionModelClient` and named typed beans only for configured defaults:

```java
import com.llmrix.model.router.core.api.audio.AudioModel;
import com.llmrix.model.router.core.api.chat.ChatModel;
import com.llmrix.model.router.core.api.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class ModelService {
    private final ChatModel chat;
    private final EmbeddingModel embeddings;
    private final AudioModel audio;

    public ModelService(
            @Qualifier("orionModelChatModel") ChatModel chat,
            @Qualifier("orionModelEmbeddingModel") EmbeddingModel embeddings,
            @Qualifier("orionModelAudioModel") AudioModel audio) {
        this.chat = chat;
        this.embeddings = embeddings;
        this.audio = audio;
    }
}
```

Available bean names are `orionModelChatModel`, `orionModelEmbeddingModel`, `orionModelAudioModel`,
`orionModelImageModel`, and `orionModelVideoModel`. The legacy
`llmrix.model.orion.default-model` property remains an alias for the Chat default.
