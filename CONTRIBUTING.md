# Contributing to LLMRix Model Router

Thank you for your interest in contributing. This document explains the process for reporting bugs, proposing features, and submitting pull requests.

## Code of Conduct

All contributors are expected to follow the [Code of Conduct](CODE_OF_CONDUCT.md).

## Reporting Issues

- Search existing issues before opening a new one.
- Use the provided issue templates for bug reports and feature requests.
- Include the Java version, Spring Boot version (if applicable), and a minimal reproducer.

## Development Setup

**Requirements:** Java 17+, Maven 3.9+, Docker (for Redis integration tests).

```bash
git clone https://github.com/llmrix/llmrix-router.git
cd llmrix-router
mvn clean verify
```

Redis integration tests require a running Redis on `localhost:6379`. Start one with:

```bash
docker run --rm -p 6379:6379 redis:7.2-alpine
```

Or set `LLMRIX_MODEL_ROUTER_REDIS_URI` to point at an existing instance.

## Making Changes

1. Fork the repository and create a branch from `main`: `git checkout -b fix/my-fix`.
2. Make your changes. Tests live in the module-scoped children under `llmrix-model-examples`.
3. Run `mvn clean verify` on Java 17 and Java 21 before pushing.
4. Open a pull request against `main`. Fill in the pull request template.

## Commit Style

Use short imperative-mood subjects (`Fix cooldown reset on retry`, not `Fixed` or `Fixes #123`). Reference issue numbers in the body when relevant.

## Pull Request Checklist

- [ ] `mvn clean verify` passes on Java 17 and Java 21.
- [ ] New behavior is covered by a test in the matching `*-examples` child under `llmrix-model-examples`.
- [ ] Public API changes are reflected in the relevant module's `package-info.java` or Javadoc.
- [ ] `CHANGELOG.md` has an entry under `[Unreleased]`.

## Versioning

The project follows [Semantic Versioning](https://semver.org/). Breaking changes to public APIs, Maven coordinates, or `llmrix.model.*` configuration require a major version bump after `1.0`.

## License

By contributing you agree that your contributions will be licensed under the [MIT License](LICENSE).
