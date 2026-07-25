# Security Policy

## Supported Versions

| Version | Supported |
|---------|-----------|
| 1.x (latest) | Yes |

Security fixes are backported to the current and previous minor release lines.

## Reporting a Vulnerability

**Do not open a public GitHub issue for security vulnerabilities.**

Please report security issues by opening a [GitHub Security Advisory](https://github.com/llmrix/llmrix-router/security/advisories/new) (private disclosure). Include:

- A description of the vulnerability and its potential impact.
- Steps to reproduce or a minimal proof-of-concept.
- Affected versions.

You will receive acknowledgement within **5 business days** and a status update within **14 business days**.

## Scope

This project is a routing SDK and does not store API keys, user data, or model responses itself. Common concerns include:

- **CRLF injection** in HTTP headers (Orion client validates all header names and values).
- **Credential exposure** — keys should be supplied via environment variables or a secret store, never hard-coded.
- **Dependency vulnerabilities** — please report transitive CVEs so we can update the affected dependency.

## Out of Scope

- Vulnerabilities in the LLM providers themselves (OpenAI, DeepSeek, etc.).
- Issues in infrastructure layers (Redis, reverse proxies, container runtimes) that are outside this library's control.
- Social engineering attacks.
