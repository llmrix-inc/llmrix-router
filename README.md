<h1 align="center">LLMRix Model Router</h1>
<p align="center">
  <strong>A production-oriented multi-model routing and orchestration framework for Java.</strong>
</p>
<p align="center">
  <em>OpenAI-compatible providers · Spring AI · LangChain4j · Semantic routing · Contextual bandits · Fugu orchestration</em>
</p>
<p align="center">
  <a href="https://github.com/llmrix-inc/llmrix-router/stargazers"><img src="https://img.shields.io/github/stars/llmrix-inc/llmrix-router?style=for-the-badge&logo=github&color=ffca28" alt="GitHub Stars"></a>
  <a href="https://github.com/llmrix-inc/llmrix-router/network/members"><img src="https://img.shields.io/github/forks/llmrix-inc/llmrix-router?style=for-the-badge&logo=github&color=8bc34a" alt="GitHub Forks"></a>
  <a href="./LICENSE"><img src="https://img.shields.io/badge/License-MIT-blue?style=for-the-badge" alt="License"></a>
  <a href="#requirements"><img src="https://img.shields.io/badge/Java-17%2B-orange?style=for-the-badge&logo=openjdk&logoColor=white" alt="Java Version"></a>
  <a href="#spring-boot"><img src="https://img.shields.io/badge/Spring%20Boot-3.x-6db33f?style=for-the-badge&logo=springboot&logoColor=white" alt="Spring Boot"></a>
  <img src="https://img.shields.io/badge/version-0.1.0--SNAPSHOT-lightgrey?style=for-the-badge" alt="Version">
</p>
<p align="center">
  <a href="#why-llmrix">Why LLMRix</a> •
  <a href="#architecture">Architecture</a> •
  <a href="#modules">Modules</a> •
  <a href="#quick-start">Quick Start</a> •
  <a href="#spring-boot">Spring Boot</a> •
  <a href="#openai-compatible-server">Server</a> •
  <a href="#orion-client">Client</a>
</p>
<p align="center">
  <a href="./README.zh-CN.md">简体中文</a> | <a href="./README.md">English</a>
</p>

LLMRix exposes multiple model providers through one stable `ChatModel` interface. It selects an eligible model using capability, quality, cost, latency, quota, and health signals, then applies bounded retries, cooldown, and fallback without leaking routing complexity into application code.

Use it as an embedded Java SDK, a Spring Boot starter, or an OpenAI-compatible routing service.

> Project status: active development. The Java API and configuration model are usable, but the current `0.x` version does not yet carry a `1.x` compatibility guarantee.

## Why LLMRix

- **One model contract** for OpenAI-compatible providers, Spring AI, LangChain4j, and custom implementations.
- **Policy separated from execution**: strategies rank candidates; the executor owns timeout, retry, quota, cooldown, and fallback correctness.
- **Streaming-safe failover**: fallback is allowed before the first emitted chunk and prohibited after output begins.
- **Local or distributed state**: zero-infrastructure local mode and Redis-backed health, leases, RPM, and TPM for multi-instance deployments.
- **OpenAI-compatible edge**: `/v1/chat/completions`, `/v1/responses`, and `/v1/models`, including SSE streaming.
- **Framework-neutral client**: Orion provides a small Java client plus optional Spring Boot auto-configuration.
- **Observable by design**: lifecycle events, Micrometer metrics, Spring Observations, request IDs, and health indicators.
- **Composable advanced routing**: semantic routing, contextual bandits, online shadow traffic, evaluation, and Fugu-style iterative orchestration.

## Architecture

[Open the interactive HTML architecture](docs/architecture/index.html), or download the [SVG](docs/architecture/llmrix-architecture.svg).

![LLMRix Model Router runtime architecture](docs/architecture/llmrix-architecture.svg)

The framework owns routing semantics and request correctness. Infrastructure remains responsible for TLS, WAF, load balancing, Redis HA, secret management, telemetry storage, and container orchestration.

## Modules

| Artifact | Responsibility |
|---|---|
| `llmrix-model-router-core` | Provider-neutral API, candidate model, strategies, execution, state SPI, quota, health, and events. |
| `llmrix-model-router-integrations` | OpenAI-compatible, Spring AI, LangChain4j, Redis, Bucket4j, ONNX, evaluation, shadow, and Fugu adapters. |
| `llmrix-model-router-spring-starter` | Router properties, auto-configuration, Micrometer/Observation, Actuator, and configuration metadata. |
| `llmrix-model-router-server` | OpenAI-compatible HTTP/SSE endpoints, authentication SPI, request IDs, and protocol error handling. |
| `llmrix-model-orion` | Lightweight framework-neutral Java client for the routing server. |
| `llmrix-model-orion-spring-starter` | Orion auto-configuration and Micrometer integration. |
| `llmrix-model-examples` | Executable examples and the centralized test suite. Not a production dependency. |

## Requirements

- Java 17 or later; Java 21 is recommended.
- Spring Boot 3.x when using either starter.
- Redis is optional and required only for shared multi-instance runtime state.

## Quick Start

### Maven

```xml
<dependency>
  <groupId>com.llmrix.model</groupId>
  <artifactId>llmrix-model-router-core</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
<dependency>
  <groupId>com.llmrix.model</groupId>
  <artifactId>llmrix-model-router-integrations</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

### Primary and fallback

```java
ChatModel model = RoutedChatModel.of(primary).fallbackTo(backup);
ChatResponse response = model.chat("Review this Java code for concurrency issues");
```

### Policy-based routing

```java
RoutedChatModel model = RoutedChatModel.builder()
    .candidate("reasoning", reasoningModel, c -> c
        .capabilities(Capability.REASONING, Capability.TOOLS)
        .inputCostPerMillion(1.25)
        .outputCostPerMillion(10.00))
    .candidate("fast", fastModel, c -> c
        .capabilities(Capability.CHAT, Capability.CODE)
        .inputCostPerMillion(0.27)
        .outputCostPerMillion(1.10))
    .strategy(Strategies.balanced())
    .timeout(Duration.ofSeconds(30))
    .maxRetries(1)
    .build();

ChatResponse response = model.chat(ChatRequest.builder()
    .userMessage("Find the race condition")
    .routingHints(RoutingHints.builder()
        .require(Capability.CODE)
        .maxCostUsd(0.05)
        .build())
    .build());
```

Applications can call synchronously, asynchronously, or as a `Flow.Publisher<ChatChunk>`. Text, images, input audio, tools, structured response formats, usage, finish reasons, and common generation options are represented by provider-neutral Core types.

## Routing Model

Every request follows one deterministic execution pipeline:

1. Validate the request and normalize routing hints.
2. Remove candidates that violate capability, model, context, cost, quota, concurrency, or health constraints.
3. Rank eligible candidates with the configured strategy.
4. Acquire runtime quota and concurrency leases.
5. Execute with a bounded per-attempt and total timeout.
6. Retry only retryable failures and only within the configured budget.
7. Mark failures, apply cooldown, and move to the next fallback.
8. Settle token usage, release leases, and publish lifecycle observations.

Built-in strategies include priority, round-robin, weighted random, balanced scoring, semantic scoring, and contextual bandit selection. Custom policies implement `RoutingStrategy`; custom runtime persistence implements `RouterStateStore` or `BanditStateStore`.

## Spring Boot

```xml
<dependency>
  <groupId>com.llmrix.model</groupId>
  <artifactId>llmrix-model-router-spring-starter</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

```yaml
llmrix:
  model:
    router:
      default-route: general
      routes:
        general:
          strategy: balanced
          candidates: [primary, backup]
          fallbacks: [backup]
      execution:
        timeout: 30s
        max-retries: 1
      state:
        mode: local # use redis for shared multi-instance limits and health
      candidates:
        primary:
          provider: openai-compatible
          base-url: https://api.openai.com/v1
          api-key: ${OPENAI_API_KEY}
          model-name: gpt-4.1-mini
          capabilities: [chat, tools, code]
        backup:
          provider: openai-compatible
          base-url: https://api.deepseek.com/v1
          api-key: ${DEEPSEEK_API_KEY}
          model-name: deepseek-chat
          capabilities: [chat, code]
```

The starter creates routed model beans, backs off when applications provide their own beans, validates invalid candidate configuration at startup, and contributes health and telemetry integration when the corresponding host libraries are present.

### Shared state

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

Redis mode is fail-closed: invalid configuration or an unavailable store does not silently downgrade global limits to JVM-local limits. The implementation uses Lettuce and atomic Lua operations; it does not require Redisson.

## OpenAI-Compatible Server

The optional server exposes:

| Endpoint | Support |
|---|---|
| `POST /v1/chat/completions` | Synchronous and SSE streaming chat completions. |
| `POST /v1/responses` | Core Responses API subset with explicit rejection of unsupported semantic fields. |
| `GET /v1/models` | Available routed model and route identifiers. |

It supports opaque Bearer keys through `ApiKeyVerifier`, propagates or generates `X-Request-Id`, and emits OpenAI-shaped errors. It intentionally does not issue keys, manage tenants, or store secrets; those responsibilities belong to an IAM or secret-management system.

## Orion Client

```java
OrionModelClient client = OrionModelClient.builder()
    .baseUrl("https://router.example.com/v1")
    .apiKey(System.getenv("LLMRIX_API_KEY"))
    .build();

ChatModel model = client.chatModel("support-route");
ChatResponse response = model.chat("Summarize this incident");
```

Request-level options support request IDs and safe custom headers. Authorization cannot be overridden dynamically, and header names and values are validated against CRLF injection.

## Reliability and Streaming

- Retry and fallback apply only to failures classified as retryable.
- A streaming request may switch candidates before its first chunk, never after data is visible to the caller.
- Cancellation propagates to the active candidate and releases runtime state.
- Tool-bearing requests must not be blindly replayed because tools may have side effects.
- Upstream HTTP status is retained on provider-domain exceptions; non-HTTP failures use `-1`.
- Online shadow execution is isolated by sampling, timeout, and concurrency limits and skips tool requests by default.

## Observability

Router and Fugu lifecycle listeners are the stable Core observability boundary. Optional Spring integration provides Micrometer counters/timers, first-token latency, Observation context, Actuator health, and request-ID correlation. Orion exposes its own dependency-free listener SPI and adapts it to Micrometer when used through Spring.

The framework emits telemetry but does not deploy Prometheus, Grafana, an OpenTelemetry Collector, or log storage.

## Fugu Orchestration

`FuguOrchestrator` implements iterative Candidate/Role selection for solver-reviewer and refinement workflows. It supports rule-based or ONNX policies, bounded turns, retry/fallback, optional shared cooldown state, and a lifecycle `Flow.Publisher`. The lifecycle stream represents orchestration events, not model token chunks.

Training, reward modeling, and policy rollout stay offline. Runtime policy manifests are versioned and validated before inference.

## Extension Points

| SPI | Use it to |
|---|---|
| `ChatModel` / streaming model contract | Integrate a provider or an existing model client. |
| `RoutingStrategy` | Implement business-specific candidate ordering. |
| `RouterStateStore` | Persist health, quota, and concurrency state. |
| `BanditStateStore` | Share contextual-bandit selections and rewards. |
| Router/Fugu listeners | Export traces, metrics, audit events, or feedback. |
| `ApiKeyVerifier` | Connect the server to external identity infrastructure. |
| Candidate factory registry | Add provider types to Spring Boot configuration. |

## Build and Test

```bash
mvn clean test
mvn package -DskipTests
```

Tests are centralized in `llmrix-model-examples`. Redis integration tests require a real Redis instance; HTTP/SSE tests require permission to bind a local port. CI is expected to run both on Java 17 and Java 21.

## Scope

LLMRix is a routing framework, not an API gateway, model host, training platform, secret manager, or infrastructure control plane. Deploy it behind Higress, APISIX, Nginx, or a cloud load balancer when gateway features are required. Provider keys should be supplied through the deployment environment or a managed secret store.

## Compatibility

The project follows Semantic Versioning. After `1.0`, public Java APIs, Maven coordinates, protocol behavior, and `llmrix.model.*` configuration remain backward compatible within a major line. Experimental APIs are explicitly documented and excluded from that guarantee.

## Contributing

Bug reports, feature requests, and pull requests are welcome — see [CONTRIBUTING.md](CONTRIBUTING.md). Please read the [Code of Conduct](CODE_OF_CONDUCT.md) before participating.

## Security

To report a vulnerability, follow the [Security Policy](SECURITY.md). Do not open a public issue.

## Changelog

Release history is maintained in [CHANGELOG.md](CHANGELOG.md).

## License

MIT License — see [LICENSE](LICENSE).
