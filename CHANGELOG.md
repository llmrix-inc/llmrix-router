# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.1.0] - 2026-07-25

### Added
- `llmrix-model-router-core`: provider-neutral `ChatModel` API, candidate model, routing strategies (priority, round-robin, weighted random, balanced, semantic, contextual bandit), deterministic execution pipeline with quota, health, cooldown, retry, and lifecycle events.
- `llmrix-model-router-integrations`: OpenAI-compatible client, Spring AI adapter, LangChain4j adapter, Redis state store (Lettuce + Lua), Bucket4j quota, ONNX policy, online shadow execution, offline evaluation, and Fugu iterative orchestration.
- `llmrix-model-router-spring-starter`: `llmrix.model.router.*` configuration properties, auto-configuration, Micrometer metrics, Spring Observations, Actuator health indicator, first-token latency, and configuration metadata.
- `llmrix-model-router-server`: OpenAI-compatible HTTP/SSE endpoints (`/v1/chat/completions`, `/v1/responses`, `/v1/models`), `ApiKeyVerifier` SPI, `X-Request-Id` propagation, and OpenAI-shaped error responses.
- `llmrix-model-orion`: lightweight framework-neutral Java client with `OrionModelClientListener` SPI, CRLF-safe custom headers, and request-level options.
- `llmrix-model-orion-spring-starter`: Orion auto-configuration and Micrometer integration.
- `llmrix-model-examples`: centralized test suite covering all modules; Redis and HTTP integration tests.

[Unreleased]: https://github.com/llmrix/llmrix-router/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/llmrix/llmrix-router/releases/tag/v0.1.0
