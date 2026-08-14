package net.messeb.volt;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoltIntegrationEdgeTest {
    @Test
    void failedCorsMutationAfterTestDispatchDoesNotLeakIntoErrorResponses() {
        try (var app = app()
                .cors(Cors.allowOrigin("https://allowed.example"))
                .get("/fail", context -> {
                    throw new BadRequest("failed");
                })) {
            VoltTest.request(app).get("/fail");

            assertThrows(
                    IllegalStateException.class,
                    () -> app.cors(Cors.allowOrigin("https://leaked.example")));

            var response = VoltTest.request(app)
                    .header("Origin", "https://leaked.example")
                    .get("/fail");

            assertEquals(400, response.status());
            assertNull(response.header("Access-Control-Allow-Origin"));
        }
    }

    @Test
    void duplicateContentLengthsMustAgree() {
        try (var app = app().post("/body", context ->
                context.text(Integer.toString(context.bodyBytes().length)))) {
            var accepted = VoltTest.request(app)
                    .header("Content-Length", "4")
                    .addHeader("Content-Length", "4")
                    .body("volt")
                    .post("/body");
            var rejected = VoltTest.request(app)
                    .header("Content-Length", "3")
                    .addHeader("Content-Length", "4")
                    .body("volt")
                    .post("/body");

            assertEquals(200, accepted.status());
            assertEquals("4", accepted.bodyString());
            assertEquals(400, rejected.status());
            assertEquals(
                    "{\"error\":\"conflicting Content-Length headers\"}",
                    rejected.bodyString());
        }
    }

    @Test
    void invalidResponseHeadersAndContentTypesBecomeSafeServerErrors() {
        try (var app = app()
                .get("/header", context -> context.header("Bad Header", "value").empty())
                .get("/unicode-header", context -> context.header("X-Tést", "value").empty())
                .get("/nul-value", context -> context.header("X-Test", "bad\0value").empty())
                .get("/del-value", context -> context.header("X-Test", "bad\u007fvalue").empty())
                .get("/content-type", context ->
                        context.bytes(new byte[]{1}, "text/plain\r\nX-Injected: yes"))) {
            var header = VoltTest.request(app).get("/header");
            var unicodeHeader = VoltTest.request(app).get("/unicode-header");
            var nulValue = VoltTest.request(app).get("/nul-value");
            var delValue = VoltTest.request(app).get("/del-value");
            var contentType = VoltTest.request(app).get("/content-type");

            assertEquals(500, header.status());
            assertEquals("{\"error\":\"internal server error\"}", header.bodyString());
            assertThrows(IllegalArgumentException.class, () -> header.header("Bad Header"));
            assertEquals(500, unicodeHeader.status());
            assertEquals(500, nulValue.status());
            assertEquals(500, delValue.status());
            assertEquals(500, contentType.status());
            assertEquals("{\"error\":\"internal server error\"}", contentType.bodyString());
            assertNull(contentType.header("X-Injected"));
        }
    }

    @Test
    void exceptionMapperErrorsCannotAbandonTheResponseLifecycle() {
        try (var app = app()
                .get("/", context -> {
                    throw new IllegalStateException("original");
                })
                .exception(IllegalStateException.class, (failure, context) -> {
                    throw new AssertionError("mapper failed");
                })) {
            var response = VoltTest.request(app).get("/");

            assertEquals(500, response.status());
            assertEquals("{\"error\":\"internal server error\"}", response.bodyString());
        }
    }

    @Test
    void afterMiddlewareRunsForMappedFailuresAndRoutingErrors() {
        try (var app = app()
                .after(context -> context.addHeader("X-After", context.requestPath()))
                .get("/fail", context -> {
                    throw new BadRequest("failed");
                })) {
            var mapped = VoltTest.request(app).get("/fail");
            var missing = VoltTest.request(app).get("/missing");
            var wrongMethod = VoltTest.request(app).post("/fail");

            assertEquals(400, mapped.status());
            assertEquals("/fail", mapped.header("X-After"));
            assertEquals(404, missing.status());
            assertEquals("/missing", missing.header("X-After"));
            assertEquals(405, wrongMethod.status());
            assertEquals("/fail", wrongMethod.header("X-After"));
        }
    }

    @Test
    void routeOptionsAreWrappedOnceAndRetainStateAcrossRequests() {
        var wraps = new AtomicInteger();
        RouteOption counting = RouteOption.of("counting", next -> {
            wraps.incrementAndGet();
            var requests = new AtomicInteger();
            return context -> {
                next.handle(context);
                context.header("X-Route-Request", Integer.toString(requests.incrementAndGet()));
            };
        });
        try (var app = app()
                .routeOption(counting)
                .get("/", context -> context.text("ok"))) {
            var first = VoltTest.request(app).get("/");
            var second = VoltTest.request(app).get("/");

            assertEquals("1", first.header("X-Route-Request"));
            assertEquals("2", second.header("X-Route-Request"));
            assertEquals(1, wraps.get());
        }
    }

    @Test
    void formBodyIoDoesNotRunWhileHoldingTheContextMonitor() {
        var currentContext = new AtomicReference<Context>();
        var monitorHeldDuringRead = new AtomicBoolean();
        byte[] form = "name=Ada".getBytes(StandardCharsets.UTF_8);
        var input = new ByteArrayInputStream(form) {
            @Override
            public synchronized int read(byte[] target, int offset, int length) {
                Context context = currentContext.get();
                if (context != null && Thread.holdsLock(context)) {
                    monitorHeldDuringRead.set(true);
                }
                return super.read(target, offset, length);
            }
        };
        try (var app = app().post("/", context -> {
            currentContext.set(context);
            context.text(context.formParam("name"));
        })) {
            var exchange = new InMemoryExchange(
                    "POST",
                    "/",
                    Map.of("Content-Type", List.of("application/x-www-form-urlencoded")),
                    new byte[0]);
            exchange.setStreams(input, null);

            app.dispatchForTest(exchange);
            InMemoryExchange.Result result = exchange.result();

            assertEquals("Ada", new String(result.body(), StandardCharsets.UTF_8));
            assertFalse(monitorHeldDuringRead.get());
        }
    }

    @Test
    void concurrentBodyAndFormAccessShareOneBodyRead() {
        var endOfStreamReads = new AtomicInteger();
        byte[] form = "name=Ada".getBytes(StandardCharsets.UTF_8);
        var input = new ByteArrayInputStream(form) {
            @Override
            public synchronized int read(byte[] target, int offset, int length) {
                int result = super.read(target, offset, length);
                if (result == -1) {
                    endOfStreamReads.incrementAndGet();
                }
                return result;
            }
        };
        try (var app = app().post("/", context -> {
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var body = executor.submit(context::bodyString);
                var name = executor.submit(() -> context.formParam("name"));
                context.text(body.get() + ":" + name.get());
            }
        })) {
            var exchange = new InMemoryExchange(
                    "POST",
                    "/",
                    Map.of("Content-Type", List.of("application/x-www-form-urlencoded")),
                    new byte[0]);
            exchange.setStreams(input, null);

            app.dispatchForTest(exchange);
            InMemoryExchange.Result result = exchange.result();

            assertEquals("name=Ada:Ada", new String(result.body(), StandardCharsets.UTF_8));
            assertEquals(1, endOfStreamReads.get());
        }
    }

    @Test
    void voltTestUsesTheServersConfiguredCodecAndHandlesHugeTimeouts() {
        var encodes = new AtomicInteger();
        var decodes = new AtomicInteger();
        JsonCodec codec = new JsonCodec() {
            @Override
            public byte[] encode(Object value) {
                encodes.incrementAndGet();
                return "custom".getBytes(StandardCharsets.UTF_8);
            }

            @Override
            public <T> T decode(byte[] json, Class<T> type) {
                decodes.incrementAndGet();
                return type.cast(new Person("Ada"));
            }
        };
        try (var app = app()
                .jsonCodec(codec)
                .post("/", context -> context.json(context.body(Person.class)))) {
            var response = VoltTest.request(app)
                    .timeout(Duration.ofSeconds(Long.MAX_VALUE))
                    .json(new Person("request"))
                    .post("/");

            assertEquals(200, response.status());
            assertEquals(new Person("Ada"), response.body(Person.class));
            assertEquals(2, encodes.get());
            assertEquals(2, decodes.get());
        }
    }

    @Test
    void customCodecBuffersAreCopiedAtRequestAndResponseBoundaries() {
        byte[] requestBuffer = "request".getBytes(StandardCharsets.UTF_8);
        byte[] responseBuffer = "response".getBytes(StandardCharsets.UTF_8);
        var encodes = new AtomicInteger();
        JsonCodec codec = new JsonCodec() {
            @Override
            public byte[] encode(Object value) {
                return encodes.getAndIncrement() == 0 ? requestBuffer : responseBuffer;
            }

            @Override
            public <T> T decode(byte[] json, Class<T> type) {
                throw new UnsupportedOperationException();
            }
        };
        try (var app = app()
                .jsonCodec(codec)
                .post("/", context -> {
                    assertEquals("request", context.bodyString());
                    context.json("response");
                    responseBuffer[0] = 'X';
                })) {
            VoltTest.Request request = VoltTest.request(app).json("request");
            requestBuffer[0] = 'X';

            var response = request.post("/");

            assertEquals(200, response.status());
            assertEquals("response", response.bodyString());
        }
    }

    @Test
    void resetContentResponsesDeclareAnEmptyRepresentation() {
        try (var app = app().get("/", context -> context.status(205).empty())) {
            var response = VoltTest.request(app).get("/");

            assertEquals(205, response.status());
            assertEquals("0", response.header("Content-Length"));
            assertEquals("", response.bodyString());
        }
    }

    @Test
    void fractionalStopGraceDoesNotRoundUpToAFullSecond() throws Exception {
        var entered = new CountDownLatch(1);
        var blocked = new CountDownLatch(1);
        try (var app = app().get("/", context -> {
            entered.countDown();
            blocked.await();
            context.text("late");
        }).start()) {
            var request = HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + app.localPort() + "/"))
                    .GET()
                    .build();
            HttpClient.newHttpClient().sendAsync(
                    request,
                    HttpResponse.BodyHandlers.discarding());
            assertTrue(entered.await(2, TimeUnit.SECONDS));

            long started = System.nanoTime();
            app.stop(Duration.ofMillis(20));
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertTrue(elapsedMillis < 700, "stop took " + elapsedMillis + " ms");
        }
    }

    private Volt app() {
        return Volt.port(0).shutdownHook(false);
    }

    private record Person(String name) {
    }
}
