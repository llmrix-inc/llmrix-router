# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- **Multi-modal core API**: unified `ModelClient` interface with `ModelRequest` / `RoutedResponse` abstractions supporting chat, embedding, audio, image, and video modalities.
- **Modular API packages**: `api/chat`, `api/embedding`, `api/audio`, `api/image`, and `api/video` sub-packages with modality-specific model, request, and response types.
- **New core packages**: `engine` (execution policy and routed operations), `event` (lifecycle events and listener SPI), `model` (target, capability, limits, pricing), `runtime` (`LlmRouter` builder and facade), `state` (health, quota, state store), and `stream` (streaming utilities).
- **Provider SPI**: `ModelProvider` and `ModelProviderRequest` in `spi.provider` for pluggable provider integrations.
- **Authentication SPI**: `ProviderAuthenticator` and `RequestAuthenticator` in `spi.auth` for flexible authentication strategies.
- **Cost SPI**: `ModelPricingResolver` and `PricingContext` in `spi.cost` for usage cost calculation.
- **First-class providers**: built-in `openai`, `deepseek`, and `openrouter` providers with official default endpoints and OpenRouter application attribution headers.
- **OpenAI-compatible multi-modal integrations**: `OpenAiCompatibleAudioModel`, `OpenAiCompatibleEmbeddingModel`, `OpenAiCompatibleImageModel`, and `OpenAiCompatibleVideoModel`.
- **OpenAI HTTP endpoints in Spring starter**: audio, embedding, image, and video controllers alongside the existing chat endpoint, all under `llmrix.model.router.http.enabled`.
- **`LlmRouter` runtime facade**: programmatic builder API (`LlmRouterBuilder`) for assembling router instances.
- **`ModelTargetRegistry`**: replaces `CandidateFactoryRegistry` in the Spring starter for managing model targets and providers.
- **`ObservingModelOperations`**: generic observation-aware model operations wrapper in the Orion client.
- **Module-scoped examples**: `llmrix-model-router-core-examples`, `llmrix-model-router-integrations-examples`, `llmrix-model-router-spring-starter-examples`, `llmrix-model-router-server-examples`, and `llmrix-model-client-examples` as child modules under `llmrix-model-examples`.
- **GitHub Actions CI workflow**: automatic build and test verification on pull requests and pushes to `main`.
- **GitHub Actions auto-release workflow**: automatic tag and GitHub Release creation when code is merged to `main`, with changelog extraction from `CHANGELOG.md`.
- **Additional Spring configuration metadata**: JSON metadata for IDE auto-completion of router properties.
- **Lombok configuration**: project-wide `lombok.config` for consistent annotation processing.

### Changed

- Restructured the core API around a modality-agnostic `ModelClient` / `ModelRequest` / `RoutedResponse` model instead of a chat-only `ChatModel` API.
- Moved chat-specific types (`ChatRequest`, `ChatResponse`, `Message`, `ToolCallPart`, etc.) from `api` into the `api.chat` sub-package.
- Relocated execution pipeline types from `execution` to `engine` (execution policy, routed model operations) and `state` (health, quota, state store).
- Moved lifecycle event types from `spi.event` to the top-level `event` package with the `RouterListener` SPI.
- Renamed `Candidate` to `RouteCandidate` and `Candidate*` state types to `Target*` for clearer terminology.
- Replaced the generic Spring configuration providers with first-class `openai`, `deepseek`, and `openrouter` providers.
- Added official default API endpoints and OpenRouter application attribution headers.
- Moved the OpenAI-compatible HTTP protocol, authentication, request ID, and error handling into the Spring starter. The standalone Spring Boot launch shell now lives in `llmrix-model-router-server-examples`, and HTTP exposure is controlled by `llmrix.model.router.http.enabled`.
- Renamed `router.candidates` and `routes.*.candidates` configuration to `integrations`.
- Converted `llmrix-model-examples` into a Maven aggregator with one `*-examples` child per Router or client production module, keeping examples and tests aligned with module boundaries.
- Normalized the standalone server example configuration to environment-backed provider credentials and a consistent model pool.
- Refactored the Spring starter auto-configuration to use `ModelTargetRegistry` and provider-based integration setup.
- Enhanced `.gitignore` with comprehensive rules for IDEs, build tools, OS files, credentials, and environment configurations.
- Updated Orion client and Spring starter to work with the new multi-modal model operations abstraction.

### Removed

- Removed `llmrix-model-router-server` standalone module; HTTP endpoints are now part of the Spring starter and the runnable example is in `llmrix-model-router-server-examples`.
- Removed Spring AI provider implementation, tests, and dependencies.
- Removed LangChain4j provider implementation, tests, and dependencies.
- Removed the generic `Bean` provider abstraction and `CandidateFactoryRegistry`.
- Removed route-level `fallbacks` configuration, fallback execution branches, and fallback lifecycle metrics. Every route now uses only its `models` pool; when no model remains available, execution raises `ModelUnavailableException` and the HTTP layer returns `503 Service Unavailable`.
- Removed `README.zh-CN.md` (consolidated to single-language documentation).
- Removed default fallback lists from the server example because route models already provide load balancing and failure continuation.

### Fixed

- Skip examples modules (`maven.deploy.skip`, `gpg.skip`, `maven.source.skip`, `maven.javadoc.skip`) during Maven Central deployment to avoid publishing example artifacts.

## [1.0.0] - 2026-07-25

### Added
- `llmrix-model-router-core`: provider-neutral `ChatModel` API, candidate model, routing strategies (priority, round-robin, weighted random, balanced, semantic, contextual bandit), deterministic execution pipeline with quota, health, cooldown, retry, and lifecycle events.
- `llmrix-model-router-integrations`: OpenAI-compatible client, Spring AI adapter, LangChain4j adapter, Redis state store (Lettuce + Lua), Bucket4j quota, ONNX policy, online shadow execution, offline evaluation, and Fugu iterative orchestration.
- `llmrix-model-router-spring-starter`: `llmrix.model.router.*` configuration properties, auto-configuration, Micrometer metrics, Spring Observations, Actuator health indicator, first-token latency, and configuration metadata.
- `llmrix-model-router-server`: executable Spring Boot launch shell for the router starter.
- `llmrix-model-orion`: lightweight framework-neutral Java client with `OrionModelClientListener` SPI, CRLF-safe custom headers, and request-level options.
- `llmrix-model-orion-spring-starter`: Orion auto-configuration and Micrometer integration.
- `llmrix-model-examples`: Maven examples aggregator with module-scoped child projects; Redis and HTTP integration tests.
- Maven Central deployment configuration and project metadata normalization.

[Unreleased]: https://github.com/llmrix/llmrix-router/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/llmrix/llmrix-router/releases/tag/v1.0.0
