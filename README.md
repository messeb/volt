# Volt

[![PR](https://github.com/messeb/volt/actions/workflows/pr.yml/badge.svg)](https://github.com/messeb/volt/actions/workflows/pr.yml)
[![GitHub release](https://img.shields.io/github/v/release/messeb/volt)](https://github.com/messeb/volt/releases)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

Volt is a small JDK 21+ HTTP framework with an Express-like fluent API. The core
uses `com.sun.net.httpserver`, virtual threads, and no mandatory runtime
dependencies.

Create an app with `Volt.create()` for port 8080, `Volt.create(port)` for an
explicit port, or the terse `Volt.port(port)` alias. All three return the same
fluent `Volt` API; the port is fixed at creation.

## Installation

Volt packages are published to GitHub Packages. Replace `VERSION` with a
released version:

```xml
<repositories>
    <repository>
        <id>github</id>
        <url>https://maven.pkg.github.com/messeb/volt</url>
    </repository>
</repositories>

<dependencies>
    <dependency>
        <groupId>net.messeb</groupId>
        <artifactId>volt</artifactId>
        <version>VERSION</version>
    </dependency>
</dependencies>
```

GitHub Packages requires Maven credentials. Configure a `github` server in
`~/.m2/settings.xml` using your GitHub username and a token with
`read:packages`. Never commit that token.

## Quick start

```java
import net.messeb.volt.Env;
import net.messeb.volt.Volt;

record User(String name, int age) {}

public class Main {
    public static void main(String[] args) {
        Volt.port(Env.get("PORT", 8080))
            .get("/health", ctx -> ctx.text("ok"))
            .get("/users/{id}", ctx -> ctx.json(
                new User(ctx.pathRequired("id"), ctx.queryInt("age", 18))))
            .post("/users", ctx -> ctx.status(201).json(ctx.body(User.class)))
            .start();
    }
}
```

The equivalent explicit factory spelling is:

```java
var app = Volt.create(8080)
    .apiKey("X-Api-Key", Keys::isValid)
    .get("/users/{id}", ctx -> ctx.json(repo.find(ctx.path("id"))))
    .start();
```

The built-in JSON codec encodes scalars, string-keyed maps, collections, arrays,
enums, and records. Decoding supports scalar/array/enum/record class tokens,
untyped maps/lists, and `List`, `Set`, or string-keyed `Map` record components.
Record decoding is strict: missing and unknown properties, invalid UTF-8,
duplicate object keys, unsupported generic targets, and excessive nesting are
rejected. Configure another implementation with `server.jsonCodec(yourCodec)`
when an application needs general POJO binding. Configured `JsonCodec`, `Handler`,
and custom `RouteOption` instances are shared across concurrent virtual-thread
requests and must be thread-safe.

## Routing and request data

```java
var app = Volt.port(8080)
    .get("/search", ctx -> ctx.json(Map.of(
        "query", ctx.queryRequired("q"),
        "tags", ctx.queryAll("tag"),
        "active", ctx.queryBool("active", true))))
    .get("/files/*", ctx -> ctx.text(ctx.path("*")))
    .path("/api/v1", api -> api
        .get("/items/{id}", ctx -> ctx.text(ctx.pathRequired("id"))))
    .post("/form", ctx -> ctx.text(ctx.formParam("email")));
```

Request helpers include `header`, `headerRequired`, `contentType`, `userAgent`,
`bearerToken`, `bodyBytes`, and `bodyString`. Missing required values and invalid
typed query parameters automatically become JSON `400` responses.
Each `Context` is request-confined: use it only during its handler/middleware
chain, and do not retain it or share it with application-created threads.

The request-body limit defaults to 1 MiB:

```java
Volt.port(8080).maxBodySize(2_000_000);
```

Oversized requests receive `413`. Request header limits can be tightened with
`maxRequestHeaders(count, bytes)`; the JDK transport only exposes headers after
parsing, so a reverse proxy should enforce wire-level limits for Internet-facing
deployments. Form helpers parse only
`application/x-www-form-urlencoded` bodies and reject malformed percent encoding
or invalid UTF-8.

Body and header limits bound raw bytes/fields, not decoded JSON object graphs,
form/query parameter counts, or request-line/path length. Internet-facing
deployments should enforce request-line and URI limits at a reverse proxy and
apply application-specific complexity limits before processing untrusted data.

Body and header limits bound raw bytes, not JSON/form token counts or expanded
object graphs, and Volt does not configure a request-line limit. Internet-facing
deployments should enforce request-line/body limits at a reverse proxy and apply
application-level collection/parameter limits for untrusted payloads.

## Authentication

```java
var app = Volt.port(8080)
    .apiKey("X-Api-Key", key -> Auth.constantTimeEquals(key, expectedKey))
    .get("/health", ctx -> ctx.text("ok"), Auth.none())
    .get("/account", ctx -> ctx.text(ctx.attribute("apiKey")))
    .get("/admin", ctx -> ctx.text("admin"),
        Auth.bearer(adminTokens::contains))
    .get("/download", ctx -> ctx.text("file"),
        Auth.query("api_key", downloadKeys::contains));
```

Failed authentication returns `401`, a `WWW-Authenticate` header, and a safe JSON
error without invoking the route. Volt's built-in auth failures and debug
descriptions do not include key values. Applications and validators must avoid
putting keys in exception messages or logs; `Auth.mask(key)` is available for
application debug messages.

Prefer a header or bearer credential in production: query-string keys can leak
through browser history, access logs, and proxy telemetry. `constantTimeEquals`
avoids early-exit equality checks by comparing hashes, but hashing work still
scales with input length; enforce a reasonable credential-length limit.
Applications remain responsible for rotation, hashed-at-rest key storage where
applicable, and avoiding logs of the raw `apiKey` attribute.

## Responses, middleware, CORS, and errors

```java
var app = Volt.port(8080)
    .before("/api/*", ctx -> ctx.attribute("started", System.nanoTime()))
    .after("/api/*", ctx -> ctx.header("X-Service", "catalog"))
    .cors(Cors.allowOrigin("https://app.example.com")
        .allowMethods("GET", "POST")
        .allowHeaders("Content-Type")
        .allowCredentials())
    .get("/api/page", ctx -> ctx.html("<h1>Volt</h1>"))
    .get("/old", ctx -> ctx.redirect("/api/page"))
    .exception(NoSuchElementException.class,
        (error, ctx) -> ctx.status(404).json(Map.of("error", "not found")));
```

`before` middleware can short-circuit by selecting a response. Matching `after`
middleware runs in registration order for normal, short-circuited, mapped-error,
and routing-error responses once the request path has parsed. Failures during
earlier request validation/path parsing, or inside `after` middleware itself, do
not restart the complete `after` chain. `Context` response mutation is confined
to the request handler thread. Throw `BadRequest`, `Unauthorized`, `NotFound`,
`MethodNotAllowed`, `PayloadTooLarge`, or another `HttpException` for an
automatic status and safe JSON error. Unmapped exceptions become a generic
`500`; details are logged but never sent to the client.

CORS response headers do not provide authorization. Use explicit origins when
possible; `allowAll().allowCredentials()` reflects the requesting origin rather
than emitting the invalid `*` plus credentials combination.

Error handling resets the selected response and its headers before mapping the
failure. Required request IDs or security headers should therefore be restored
by an exception mapper or matching `after` middleware. Configured CORS headers
are reapplied separately to error responses.

## Tests

`VoltTest` dispatches through the real router and `Context` with an in-memory
`HttpExchange`, so tests use no sockets or external client dependency:

```java
@Test
void createsUser() {
    try (var app = Volt.port(0)
            .shutdownHook(false)
            .post("/users", ctx -> ctx.status(201).json(ctx.body(User.class)))) {
        var response = VoltTest.request(app)
            .header("X-Request-Id", "test-1")
            .json(new User("Ada", 36))
            .post("/users");

        assertEquals(201, response.status());
        assertEquals(new User("Ada", 36), response.body(User.class));
    }
}
```

The client does not start or bind the network transport. It supports text,
bytes, JSON, forms, repeated headers, redirects-as-responses, and timeouts.
`request.json(value)` and `response.body(Person.class)` automatically use the
server's configured codec; overloads accepting a `JsonCodec` can override it for
an individual assertion.

`VoltTest` exercises Volt's router and context, not the JDK network parser. It
does not reproduce transport-added headers, real addresses, chunking, socket
timing, or parser behavior. Its timeout interrupts the in-memory handler, unlike
a socket-client timeout that may leave server work running.

## Lifecycle

`start()` is non-blocking. Use `stop(Duration)` or try-with-resources for graceful
shutdown. A JVM shutdown hook is installed by default and can be disabled before
startup with `shutdownHook(false)`. The JDK `HttpServer` shutdown API uses
whole-second transport delays. Volt rounds that transport delay down so
`stop(Duration)` does not extend the caller's grace period; sub-second grace
therefore stops transport admission immediately and cannot guarantee draining.
Shutdown is best-effort, and handlers that ignore interruption may outlive the
requested grace period.

## Current scope

This snapshot does not yet provide built-in rate limiting, request logging,
metrics, TLS configuration, or a packaged Jackson adapter. Middleware hooks and
`JsonCodec` allow applications to supply those concerns without changing the
core. PRD startup, heap, throughput, and sub-50-KB jar figures remain targets,
not verified guarantees; the current core jar is above the proposed size target.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) and the
[Code of Conduct](CODE_OF_CONDUCT.md).

## Support and security

Use [SUPPORT.md](SUPPORT.md) for help. Report vulnerabilities privately as
described in [SECURITY.md](SECURITY.md).

## License

Volt is licensed under the [MIT License](LICENSE).
