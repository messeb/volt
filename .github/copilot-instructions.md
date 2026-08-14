# Copilot Instructions

## Project Overview

Volt is a minimal, dependency-free Java HTTP framework built on the JDK
`HttpServer`. Its public API prioritizes an Express-like fluent developer
experience, explicit behavior, and Java type safety.

## Tech Stack

- Language: Java 21
- Build: Maven
- Transport: `jdk.httpserver`
- Concurrency: virtual threads with a blocking handler model
- Tests: JUnit Jupiter plus Volt's in-memory `VoltTest` client
- Runtime dependencies: none

## Code Conventions

- Use package `net.messeb.volt`.
- Keep `Volt` as the branded fluent entry point.
- Preserve zero mandatory runtime dependencies.
- Avoid dependency injection, annotations, classpath scanning, and async public
  APIs.
- Validate input at public and HTTP boundaries and return explicit errors.
- Keep `Context` request-confined and do not perform blocking I/O while holding
  monitors.
- Preserve HTTP semantics for HEAD, 204/205, 404/405, content lengths, headers,
  middleware, and exception mapping.
- Never log credentials or expose internal exception details in responses.
- Prefer focused classes and direct implementations over speculative
  abstractions.

## Testing

- Run all checks with `mvn --batch-mode verify`.
- Add unit tests for isolated parsing and validation behavior.
- Add integration tests for routing, lifecycle, concurrency, security, and
  response semantics.
- Use `VoltTest` when transport-parser fidelity is not required.

## Important Context

The default JSON codec intentionally supports a bounded subset centered on
records. Internet-facing deployments require a reverse proxy for TLS and
wire-level request limits. User-visible changes belong in `CHANGELOG.md` under
`[Unreleased]`.

