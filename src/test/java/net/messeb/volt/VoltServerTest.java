package net.messeb.volt;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoltServerTest {
    private final HttpClient client = HttpClient.newHttpClient();
    private final List<Volt> servers = new ArrayList<>();

    @AfterEach
    void stopServers() {
        servers.forEach(Volt::stop);
    }

    @Test
    void brandedFactoriesReturnTheSameFluentApi() {
        try (var defaults = Volt.create().shutdownHook(false);
             var configured = Volt.create(0).shutdownHook(false);
             var terse = Volt.port(0).shutdownHook(false)) {
            assertEquals(defaults, defaults.get("/default", Context::empty));
            assertEquals(configured, configured.get("/configured", Context::empty));
            assertEquals(terse, terse.get("/terse", Context::empty));
        }

        assertThrows(IllegalArgumentException.class, () -> Volt.create(-1));
        assertThrows(IllegalArgumentException.class, () -> Volt.port(65_536));
    }

    @Test
    void routeGroupsParametersQueriesHeadersAndWildcardsReachHandlers() throws Exception {
        Volt server = start(Volt.port(0)
                .path("/api", api -> api
                        .get("/users/{id}", context -> context
                                .header("X-Handler-Thread", Boolean.toString(Thread.currentThread().isVirtual()))
                                .json(new UserPayload(
                                        context.pathRequired("id"),
                                        context.queryInt("page", 1),
                                        context.queryAll("tag"),
                                        context.headerRequired("X-Tenant"))))
                        .get("/users/me", context -> context.text("static"))
                        .get("/files/*", context -> context.text(context.path("*")))));

        HttpResponse<String> user = send(server, "/api/users/%E2%82%AC?page=3&tag=a&tag=b", "GET",
                HttpRequest.BodyPublishers.noBody(), Map.of("X-Tenant", "acme"));
        HttpResponse<String> wildcard = send(server, "/api/files/docs/readme.txt", "GET",
                HttpRequest.BodyPublishers.noBody(), Map.of());
        HttpResponse<String> staticRoute = get(server, "/api/users/me");

        assertEquals(200, user.statusCode());
        assertEquals("{\"id\":\"€\",\"page\":3,\"tags\":[\"a\",\"b\"],\"tenant\":\"acme\"}", user.body());
        assertEquals("true", user.headers().firstValue("X-Handler-Thread").orElseThrow());
        assertEquals(Integer.toString(user.body().getBytes(StandardCharsets.UTF_8).length),
                user.headers().firstValue("Content-Length").orElseThrow());
        assertEquals("docs/readme.txt", wildcard.body());
        assertEquals("static", staticRoute.body());
    }

    @Test
    void missingPathsWrongMethodsAndHeadUseCorrectHttpSemantics() throws Exception {
        Volt server = start(Volt.port(0)
                .get("/items/{id}", context -> context.text("hello"))
                .post("/items/{id}", context -> context.status(201).empty())
                .get("/empty", Context::empty));

        HttpResponse<String> missing = get(server, "/missing");
        HttpResponse<String> wrongMethod = send(server, "/items/42", "PUT",
                HttpRequest.BodyPublishers.noBody(), Map.of());
        HttpResponse<String> head = send(server, "/items/42", "HEAD",
                HttpRequest.BodyPublishers.noBody(), Map.of());
        HttpResponse<String> empty = get(server, "/empty");

        assertEquals(404, missing.statusCode());
        assertEquals("{\"error\":\"not found\"}", missing.body());
        assertEquals(405, wrongMethod.statusCode());
        assertEquals("GET, POST, HEAD", wrongMethod.headers().firstValue("Allow").orElseThrow());
        assertEquals(200, head.statusCode());
        assertEquals("", head.body());
        assertEquals("5", head.headers().firstValue("Content-Length").orElseThrow());
        assertEquals(200, empty.statusCode());
        assertEquals("", empty.body());
        assertEquals("0", empty.headers().firstValue("Content-Length").orElseThrow());
    }

    @Test
    void routeSpecificityIsComparedFromLeftToRight() throws Exception {
        Volt server = start(Volt.port(0)
                .get("/{value}/b", context -> context.text("parameter-first"))
                .get("/a/{value}", context -> context.text("static-first")));

        HttpResponse<String> response = get(server, "/a/b");

        assertEquals(200, response.statusCode());
        assertEquals("static-first", response.body());
    }

    @Test
    void requiredInputErrorsBodyLimitsAndResponseStatusAreAutomatic() throws Exception {
        Volt server = start(Volt.port(0)
                .maxBodySize(4)
                .get("/search", context -> context.text(context.queryRequired("q")))
                .post("/echo", context -> context.status(201).bytes(context.bodyBytes(), "application/octet-stream")));

        HttpResponse<String> missing = get(server, "/search");
        HttpResponse<String> created = send(server, "/echo", "POST",
                HttpRequest.BodyPublishers.ofString("volt"), Map.of());
        HttpResponse<String> tooLarge = send(server, "/echo", "POST",
                HttpRequest.BodyPublishers.ofString("large"), Map.of());

        assertEquals(400, missing.statusCode());
        assertEquals("{\"error\":\"missing query parameter: q\"}", missing.body());
        assertEquals(201, created.statusCode());
        assertEquals("volt", created.body());
        assertEquals("4", created.headers().firstValue("Content-Length").orElseThrow());
        assertEquals(413, tooLarge.statusCode());
    }

    @Test
    void globalRouteOptionsCanBeOverriddenPerRoute() throws Exception {
        Volt server = start(Volt.port(0)
                .apiKey("X-Key", key -> key.equals("secret"))
                .get("/health", context -> context.text("ok"), Auth.none())
                .get("/data", context -> context.text(context.attribute("apiKey"))));

        HttpResponse<String> health = get(server, "/health");
        HttpResponse<String> unauthorized = get(server, "/data");
        HttpResponse<String> authorized = send(server, "/data", "GET",
                HttpRequest.BodyPublishers.noBody(), Map.of("X-Key", "secret"));

        assertEquals(200, health.statusCode());
        assertEquals(401, unauthorized.statusCode());
        assertTrue(unauthorized.headers().firstValue("WWW-Authenticate").isPresent());
        assertEquals(200, authorized.statusCode());
        assertEquals("secret", authorized.body());
    }

    @Test
    void lifecycleIsExplicitAndStoppedServersCannotRestart() {
        Volt server = Volt.port(0).shutdownHook(false).get("/", Context::empty);
        servers.add(server);

        assertFalse(server.isRunning());
        assertEquals(server, server.start());
        assertTrue(server.isRunning());
        assertTrue(server.localPort() > 0);
        assertThrows(IllegalStateException.class, server::start);
        assertThrows(IllegalStateException.class, () -> server.get("/late", Context::empty));
        assertThrows(IllegalStateException.class, () -> server.maxBodySize(10));
        server.stop(Duration.ZERO);

        assertFalse(server.isRunning());
        assertThrows(IllegalStateException.class, server::start);
        assertThrows(IllegalArgumentException.class, () -> Volt.port(0)
                .get("/items/{id}", Context::empty)
                .get("/items/{name}", Context::empty));
    }

    @Test
    void gracefulStopWaitsForInFlightRequests() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var stopping = new CountDownLatch(1);
        Volt server = start(Volt.port(0).get("/slow", context -> {
            entered.countDown();
            release.await();
            context.text("done");
        }));
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.localPort() + "/slow"))
                .GET()
                .build();
        CompletableFuture<HttpResponse<String>> response =
                client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        assertTrue(entered.await(1, TimeUnit.SECONDS));

        CompletableFuture<Void> stopped = CompletableFuture.runAsync(() -> {
            stopping.countDown();
            server.stop(Duration.ofSeconds(2));
        });
        assertTrue(stopping.await(1, TimeUnit.SECONDS));
        Thread.sleep(50);
        assertFalse(stopped.isDone());
        release.countDown();

        assertEquals(200, response.get(2, TimeUnit.SECONDS).statusCode());
        assertEquals("done", response.get().body());
        stopped.get(2, TimeUnit.SECONDS);
        assertFalse(server.isRunning());
    }

    @Test
    void voltTestDispatchesWithoutBindingASocketAndFreezesConfiguration() {
        Volt server = Volt.port(0).shutdownHook(false).get("/", context -> context.text("ok"));
        servers.add(server);

        VoltTest.Response response = VoltTest.request(server).get("/");

        assertEquals(200, response.status());
        assertEquals("ok", response.bodyString());
        assertFalse(server.isRunning());
        assertThrows(IllegalStateException.class, server::localPort);
        assertThrows(IllegalStateException.class, () -> server.get("/late", Context::empty));
    }

    private Volt start(Volt server) {
        servers.add(server.shutdownHook(false).start());
        return server;
    }

    private HttpResponse<String> get(Volt server, String path) throws Exception {
        return send(server, path, "GET", HttpRequest.BodyPublishers.noBody(), Map.of());
    }

    private HttpResponse<String> send(
            Volt server,
            String path,
            String method,
            HttpRequest.BodyPublisher body,
            Map<String, String> headers) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.localPort() + path))
                .timeout(Duration.ofSeconds(5))
                .method(method, body);
        headers.forEach(request::header);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private record UserPayload(String id, int page, List<String> tags, String tenant) {
    }
}
