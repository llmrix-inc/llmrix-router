# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed

- Replaced the generic Spring configuration providers with first-class `openai`, `deepseek`, and `openrouter` providers.
- Added official default API endpoints and OpenRouter application attribution headers.
- Removed the Bean, Spring AI, and LangChain4j provider implementations, tests, and dependencies.
- Renamed `router.candidates` and `routes.*.candidates` configuration to `integrations`.
- Moved the OpenAI-compatible HTTP protocol, authentication, request ID, and error handling into the Spring starter. The standalone Spring Boot launch shell now lives in `llmrix-model-router-server-examples`, and HTTP exposure is controlled by `llmrix.model.router.http.enabled`.
- Converted `llmrix-model-examples` into a Maven aggregator with one `*-examples` child per Router or client production module, keeping examples and tests aligned with module boundaries.
- Normalized the standalone server example configuration to environment-backed provider credentials and a consistent model pool; removed default fallback lists from the example because route models already provide load balancing and failure continuation.
- Removed route-level `fallbacks` configuration, fallback execution branches, and fallback lifecycle metrics. Every route now uses only its `models` pool; when no model remains available, execution raises `ModelUnavailableException` and the HTTP layer returns `503 Service Unavailable`.

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
