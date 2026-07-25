<h1 align="center">LLMRix Model Router</h1>
<p align="center">
  <strong>面向 Java 生态、以生产可用性为目标的多模型路由与编排框架。</strong>
</p>
<p align="center">
  <em>OpenAI-compatible Provider · Spring AI · LangChain4j · 语义路由 · Contextual Bandit · Fugu 编排</em>
</p>
<p align="center">
  <a href="https://github.com/llmrix/llmrix-router/stargazers"><img src="https://img.shields.io/github/stars/llmrix/llmrix-router?style=for-the-badge&logo=github&color=ffca28" alt="GitHub Stars"></a>
  <a href="https://github.com/llmrix/llmrix-router/network/members"><img src="https://img.shields.io/github/forks/llmrix/llmrix-router?style=for-the-badge&logo=github&color=8bc34a" alt="GitHub Forks"></a>
  <a href="./LICENSE"><img src="https://img.shields.io/badge/License-MIT-blue?style=for-the-badge" alt="License"></a>
  <a href="#环境要求"><img src="https://img.shields.io/badge/Java-17%2B-orange?style=for-the-badge&logo=openjdk&logoColor=white" alt="Java Version"></a>
  <a href="#spring-boot"><img src="https://img.shields.io/badge/Spring%20Boot-3.x-6db33f?style=for-the-badge&logo=springboot&logoColor=white" alt="Spring Boot"></a>
  <img src="https://img.shields.io/badge/version-1.0.0-blue?style=for-the-badge" alt="Version">
</p>
<p align="center">
  <a href="#核心价值">核心价值</a> •
  <a href="#架构">架构</a> •
  <a href="#模块说明">模块</a> •
  <a href="#快速开始">快速开始</a> •
  <a href="#spring-boot">Spring Boot</a> •
  <a href="#openai-compatible-服务端">服务端</a> •
  <a href="#orion-client">客户端</a>
</p>
<p align="center">
  <a href="./README.zh-CN.md">简体中文</a> | <a href="./README.md">English</a>
</p>

LLMRix 将多个模型 Provider 收敛为一个稳定的 `ChatModel` 接口。框架根据能力、质量、成本、延迟、配额与健康状态筛选模型，并在不向业务泄露路由复杂度的前提下完成有界重试、冷却和跨模型降级。

它可以作为嵌入式 Java SDK、Spring Boot Starter，或者独立的 OpenAI-compatible 路由服务使用。

> 项目状态：正式发布（`1.0.0` GA）。Java API 与配置模型已达到生产可用标准，严格遵循 Semantic Versioning 语义化版本规范。

## 核心价值

- **统一模型契约**：接入 OpenAI-compatible Provider、Spring AI、LangChain4j 及自定义模型。
- **策略与执行解耦**：策略只负责候选排序；执行器统一保证超时、重试、配额、冷却和降级语义。
- **流式安全降级**：首个 chunk 输出前允许切换候选，输出开始后绝不重放请求。
- **本地与分布式状态**：本地模式零基础设施；Redis 模式支持多实例共享健康、租约、RPM 和 TPM。
- **OpenAI-compatible 接入层**：提供 `/v1/chat/completions`、`/v1/responses`、`/v1/models` 和 SSE。
- **轻量外部客户端**：Orion 同时支持普通 Java 与 Spring Boot。
- **可观测性内建**：生命周期事件、Micrometer、Spring Observation、Request ID 和健康检查。
- **高级编排可组合**：语义路由、Contextual Bandit、在线 Shadow、离线评估与 Fugu 式迭代编排。

## 架构

[打开交互式 HTML 架构图](docs/architecture/index.html)，或下载 [SVG](docs/architecture/llmrix-architecture.svg)。

![LLMRix Model Router 运行时架构](docs/architecture/llmrix-architecture.svg)

框架负责路由语义与单次请求的正确性；TLS、WAF、负载均衡、Redis 高可用、密钥托管、遥测存储和容器编排由部署基础设施负责。

## 模块说明

| Artifact | 职责 |
|---|---|
| `llmrix-model-router-core` | Provider 无关 API、候选模型、策略、执行、状态 SPI、配额、健康与事件。 |
| `llmrix-model-router-integrations` | OpenAI-compatible、Spring AI、LangChain4j、Redis、Bucket4j、ONNX、评估、Shadow 与 Fugu。 |
| `llmrix-model-router-spring-starter` | Router 配置、自动装配、Micrometer/Observation、Actuator 与配置元数据。 |
| `llmrix-model-router-server` | OpenAI-compatible HTTP/SSE、认证 SPI、Request ID 与协议错误处理。 |
| `llmrix-model-orion` | 面向外部接入的轻量、无框架 Java Client。 |
| `llmrix-model-orion-spring-starter` | Orion 自动装配与 Micrometer 适配。 |
| `llmrix-model-examples` | 可执行示例与集中测试，不作为生产依赖。 |

## 环境要求

- Java 17 及以上，推荐 Java 21。
- 使用 Starter 时需要 Spring Boot 3.x。
- Redis 为可选依赖，仅在多实例共享运行时状态时需要。

## 快速开始

### Maven

```xml
<dependency>
  <groupId>com.llmrix.model</groupId>
  <artifactId>llmrix-model-router-core</artifactId>
  <version>1.0.0</version>
</dependency>
<dependency>
  <groupId>com.llmrix.model</groupId>
  <artifactId>llmrix-model-router-integrations</artifactId>
  <version>1.0.0</version>
</dependency>
```

### 主备降级

```java
ChatModel model = RoutedChatModel.of(primary).fallbackTo(backup);
ChatResponse response = model.chat("检查这段 Java 代码的并发问题");
```

### 策略路由

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
    .userMessage("定位竞态条件")
    .routingHints(RoutingHints.builder()
        .require(Capability.CODE)
        .maxCostUsd(0.05)
        .build())
    .build());
```

应用可以同步、异步或通过 `Flow.Publisher<ChatChunk>` 流式调用。文本、图片、输入音频、工具、结构化响应、usage、finish reason 和常用生成参数均由 Provider 无关的 Core 类型表达。

## 路由执行模型

每个请求都经过一致且可解释的执行链路：

1. 校验请求并规范化路由提示。
2. 按能力、模型约束、上下文、成本、配额、并发和健康状态过滤候选。
3. 使用配置的策略对合格候选排序。
4. 获取配额与并发租约。
5. 在单次尝试和总超时边界内调用模型。
6. 仅对可重试错误执行有界重试。
7. 记录失败、进入冷却，并切换到下一个 fallback。
8. 结算 token、释放租约并发布生命周期观测。

内置策略包括优先级、轮询、加权随机、均衡评分、语义评分和 Contextual Bandit。业务策略实现 `RoutingStrategy`；自定义状态后端实现 `RouterStateStore` 或 `BanditStateStore`。

## Spring Boot

```xml
<dependency>
  <groupId>com.llmrix.model</groupId>
  <artifactId>llmrix-model-router-spring-starter</artifactId>
  <version>1.0.0</version>
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
        mode: local # 多实例共享配额和健康状态时改为 redis
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

Starter 自动创建路由模型 Bean，在业务提供自定义 Bean 时主动退让，在启动阶段校验错误候选配置，并根据宿主依赖自动接入健康检查和遥测。

### 多实例共享状态

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

Redis 模式采用 fail-closed：配置错误或状态存储不可用时，不会静默退化为 JVM 本地配额。实现使用 Lettuce 与原子 Lua，不要求引入 Redisson。

## OpenAI-Compatible 服务端

| Endpoint | 能力 |
|---|---|
| `POST /v1/chat/completions` | 同步与 SSE 流式 Chat Completions。 |
| `POST /v1/responses` | Responses API 核心子集；影响语义的未支持字段会被明确拒绝。 |
| `GET /v1/models` | 返回可用路由与模型标识。 |

服务端通过 `ApiKeyVerifier` 校验 opaque Bearer Key，透传或生成 `X-Request-Id`，并输出 OpenAI 风格错误。它不负责签发 Key、管理租户或保存密钥，这些职责应接入 IAM 或密钥管理系统。

## Orion Client

```java
OrionModelClient client = OrionModelClient.builder()
    .baseUrl("https://router.example.com/v1")
    .apiKey(System.getenv("LLMRIX_API_KEY"))
    .build();

ChatModel model = client.chatModel("support-route");
ChatResponse response = model.chat("总结本次故障");
```

请求级选项支持 Request ID 与安全的自定义 Header。Authorization 不能被动态覆盖，Header 名称和值均进行 CRLF 注入校验。

## 可靠性与流式语义

- 重试和降级仅处理被分类为可重试的错误。
- 流式请求只能在首个 chunk 前切换候选；调用方看到数据后绝不重放。
- 取消会传播至当前 Candidate，并释放运行时状态。
- 带工具的请求可能产生副作用，客户端或网关不能盲目重试。
- Provider 领域异常保留上游 HTTP status；非 HTTP 错误使用 `-1`。
- Online Shadow 由采样率、独立超时和并发上限隔离，默认跳过工具请求。

## 可观测性

Router 和 Fugu 生命周期监听器是稳定的 Core 观测边界。可选 Spring 集成提供 Micrometer 指标、首 token 延迟、Observation 上下文、Actuator 健康状态和 Request ID 关联。Orion 自身提供无依赖 Listener SPI，在 Spring 环境中自动适配 Micrometer。

框架负责产生遥测，但不负责部署 Prometheus、Grafana、OpenTelemetry Collector 或日志平台。

## Fugu 编排

`FuguOrchestrator` 面向 solver-reviewer、反思和迭代改进工作流，支持规则或 ONNX 策略、有界轮次、重试/降级、可选共享冷却状态及生命周期 `Flow.Publisher`。该事件流表达编排生命周期，不是模型 token 流。

训练、reward 建模和策略 rollout 保留在离线系统；线上 policy manifest 具有版本并在推理前完成严格校验。

## 扩展点

| SPI | 用途 |
|---|---|
| `ChatModel` / 流式模型契约 | 接入新 Provider 或现有模型客户端。 |
| `RoutingStrategy` | 实现业务专属候选排序。 |
| `RouterStateStore` | 持久化健康、配额和并发状态。 |
| `BanditStateStore` | 共享 Bandit 选择与 reward。 |
| Router/Fugu Listener | 导出 Trace、指标、审计事件或反馈。 |
| `ApiKeyVerifier` | 对接外部身份基础设施。 |
| Candidate Factory Registry | 为 Spring Boot 配置增加 Provider 类型。 |

## 构建与测试

```bash
mvn clean test
mvn package -DskipTests
```

所有测试集中在 `llmrix-model-examples`。Redis 集成测试需要真实 Redis；HTTP/SSE 测试需要本地端口绑定权限。CI 应同时在 Java 17 和 Java 21 上执行。

## 项目边界

LLMRix 是路由框架，不是 API 网关、模型托管平台、训练系统、密钥管理器或基础设施控制面。需要网关能力时，应部署在 Higress、APISIX、Nginx 或云负载均衡之后。Provider API Key 应通过部署环境或托管密钥服务注入。

## 兼容性

项目遵循 Semantic Versioning。进入 `1.0` 后，同一 major 版本内保持公开 Java API、Maven 坐标、协议行为和 `llmrix.model.*` 配置向后兼容。实验性 API 会被明确标记，不纳入稳定兼容承诺。

## 贡献

欢迎提交 Bug 报告、功能请求和 Pull Request，请参阅 [CONTRIBUTING.md](CONTRIBUTING.md)。参与前请阅读[行为准则](CODE_OF_CONDUCT.md)。

## 安全

请按照[安全政策](SECURITY.md)报告漏洞，勿开 Public Issue。

## 变更日志

版本历史维护在 [CHANGELOG.md](CHANGELOG.md)。

## License

MIT License，详见 [LICENSE](LICENSE)。
