# Changelog

All notable changes to this project will be documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- JDK 21 virtual-thread HTTP server with fluent routing.
- Path, query, header, form, and bounded request-body handling.
- API key and bearer authentication route options.
- Middleware, CORS, exception mapping, JSON support, and graceful lifecycle.
- Socket-free `VoltTest` request client.

### Changed

- The branded public entry point is `Volt`, with `create()`, `create(int)`, and
  `port(int)` factories.

[Unreleased]: https://github.com/messeb/volt/commits/main

