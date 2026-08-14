package net.messeb.volt;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeatureIntegrationTest {
    @Test
    void globalHeaderAuthSupportsNoneOverrideAndExposesIdentity() {
        var invoked = new AtomicBoolean();
        try (var app = app()
                .apiKey("X-Service-Key", key -> Auth.constantTimeEquals(key, "sk-secret"))
                .get("/health", context -> context.text("ok"), Auth.none())
                .get("/protected", context -> {
                    invoked.set(true);
                    context.text(context.attribute("apiKey"));
                })) {

            var health = VoltTest.request(app).get("/health");
            var missing = VoltTest.request(app).get("/protected");
            assertFalse(invoked.get());
            var accepted = VoltTest.request(app)
                    .header("X-Service-Key", "sk-secret")
                    .get("/protected");

            assertEquals(200, health.status());
            assertEquals("ok", health.bodyString());
            assertEquals(401, missing.status());
            assertTrue(missing.header("WWW-Authenticate").contains("X-Service-Key"));
            assertEquals("{\"error\":\"invalid or missing API key\"}", missing.bodyString());
            assertEquals(200, accepted.status());
            assertEquals("sk-secret", accepted.bodyString());
            assertTrue(invoked.get());
        }
    }

    @Test
    void invalidAuthNeverInvokesHandlerAndQueryAndBearerSourcesWork() {
        var invalidInvoked = new AtomicBoolean();
        try (var app = app()
                .get("/header", context -> {
                    invalidInvoked.set(true);
                    context.text("unexpected");
                }, Auth.apiKey("X-Key", "valid"::equals))
                .get("/query", context -> context.text(context.attribute("apiKey")),
                        Auth.query("api_key", "query-key"::equals))
                .get("/bearer", context -> context.text(context.attribute("apiKey")),
                        Auth.bearer("bearer-key"::equals))) {

            var invalid = VoltTest.request(app).header("X-Key", "wrong").get("/header");
            var query = VoltTest.request(app).get("/query?api_key=query-key");
            var bearer = VoltTest.request(app)
                    .header("Authorization", "Bearer bearer-key")
                    .get("/bearer");
            var malformedBearer = VoltTest.request(app)
                    .header("Authorization", "Bearer token with spaces")
                    .get("/bearer");

            assertEquals(401, invalid.status());
            assertFalse(invalidInvoked.get());
            assertEquals("query-key", query.bodyString());
            assertEquals("bearer-key", bearer.bodyString());
            assertEquals(401, malformedBearer.status());
            assertTrue(malformedBearer.header("WWW-Authenticate").startsWith("Bearer"));
        }
    }

    @Test
    void readsTextBytesFormsAndBuiltInRecordJson() {
        try (var app = app()
                .post("/text", context -> context.text(
                        context.bodyString() + ":" + context.bodyBytes().length))
                .post("/form", context -> context.json(Map.of(
                        "first", context.formParam("tag"),
                        "all", context.formParams("tag"))))
                .post("/json", context -> {
                    Person person = context.body(Person.class);
                    context.status(201).json(person);
                })) {

            var text = VoltTest.request(app).body("Grüße").post("/text");
            var form = VoltTest.request(app).form("tag=java&tag=web").post("/form");
            var json = VoltTest.request(app).json(new Person("Ada", 36)).post("/json");

            assertEquals("Grüße:7", text.bodyString());
            assertEquals(
                    Map.of("first", "java", "all", List.of("java", "web")),
                    JsonCodec.defaultCodec().decode(form.bodyBytes(), Object.class));
            assertEquals(201, json.status());
            assertEquals(new Person("Ada", 36), json.body(Person.class));
            assertEquals("application/json; charset=utf-8", json.header("Content-Type"));
            assertEquals(Integer.toString(json.bodyBytes().length), json.header("Content-Length"));
        }
    }

    @Test
    void applicationsCanPlugInAJsonCodecWithoutRuntimeDependencies() {
        var encoded = new AtomicBoolean();
        var decoded = new AtomicBoolean();
        JsonCodec codec = new JsonCodec() {
            @Override
            public byte[] encode(Object value) {
                encoded.set(true);
                return "{\"codec\":\"custom\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            }

            @Override
            public <T> T decode(byte[] json, Class<T> type) {
                decoded.set(true);
                return type.cast(new Person("Custom", 1));
            }
        };
        try (var app = app()
                .jsonCodec(codec)
                .post("/json", context -> context.json(context.body(Person.class)))) {

            var response = VoltTest.request(app).body("custom wire format").post("/json");

            assertEquals(200, response.status());
            assertEquals("{\"codec\":\"custom\"}", response.bodyString());
            assertTrue(encoded.get());
            assertTrue(decoded.get());
        }
    }

    @Test
    void codecFailuresStillReceiveASafeJson500Response() {
        JsonCodec broken = new JsonCodec() {
            @Override
            public byte[] encode(Object value) {
                throw new JsonException("broken encoder");
            }

            @Override
            public <T> T decode(byte[] json, Class<T> type) {
                throw new JsonException("broken decoder");
            }
        };
        try (var app = app()
                .jsonCodec(broken)
                .get("/", context -> context.json(Map.of("value", "unreachable")))) {

            var response = VoltTest.request(app).get("/");

            assertEquals(500, response.status());
            assertEquals("{\"error\":\"internal server error\"}", response.bodyString());
        }
    }

    @Test
    void malformedJsonAndOversizedBodiesBecomeSafeClientErrors() {
        try (var app = app()
                .maxBodySize(8)
                .post("/json", context -> context.json(context.body(Person.class)))
                .post("/bytes", context -> context.text(Integer.toString(context.bodyBytes().length)))) {

            var malformed = VoltTest.request(app).body("{bad}").post("/json");
            var exact = VoltTest.request(app).body("12345678").post("/bytes");
            var tooLarge = VoltTest.request(app).body("123456789").post("/bytes");

            assertEquals(400, malformed.status());
            assertEquals("{\"error\":\"malformed JSON request body\"}", malformed.bodyString());
            assertEquals(200, exact.status());
            assertEquals("8", exact.bodyString());
            assertEquals(413, tooLarge.status());
            assertEquals("{\"error\":\"request body exceeds 8 bytes\"}", tooLarge.bodyString());
        }
    }

    @Test
    void mismatchedContentLengthBecomesASafeBadRequest() {
        try (var app = app().post("/bytes", context ->
                context.text(Integer.toString(context.bodyBytes().length)))) {

            var response = VoltTest.request(app)
                    .header("Content-Length", "2")
                    .body("volt")
                    .post("/bytes");

            assertEquals(400, response.status());
            assertEquals(
                    "{\"error\":\"request body length does not match Content-Length\"}",
                    response.bodyString());
        }
    }

    @Test
    void formParsingRequiresTheExactMediaTypeAndValidUtf8() {
        try (var app = app().post("/form", context ->
                context.text(context.formParam("name") == null ? "none" : context.formParam("name")))) {

            var valid = VoltTest.request(app)
                    .header("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
                    .body("name=Grüße")
                    .post("/form");
            var wrongType = VoltTest.request(app)
                    .header("Content-Type", "application/x-www-form-urlencoded-extra")
                    .body("name=Ada")
                    .post("/form");
            var invalidUtf8 = VoltTest.request(app)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .body(new byte[]{'n', 'a', 'm', 'e', '=', (byte) 0xc3, 0x28})
                    .post("/form");

            assertEquals("Grüße", valid.bodyString());
            assertEquals("none", wrongType.bodyString());
            assertEquals(400, invalidUtf8.status());
            assertEquals(
                    "{\"error\":\"URL-encoded data is not valid UTF-8\"}",
                    invalidUtf8.bodyString());
        }
    }

    @Test
    void malformedQueryUtf8BecomesASafeBadRequest() {
        try (var app = app().get("/query", context -> context.text(context.query("q")))) {

            var response = VoltTest.request(app).get("/query?q=%C3%28");

            assertEquals(400, response.status());
            assertEquals(
                    "{\"error\":\"URL-encoded data is not valid UTF-8\"}",
                    response.bodyString());
        }
    }

    @Test
    void httpExceptionsAndMostSpecificCustomMappersAreHandledSafely() {
        try (var app = app()
                .get("/missing", context -> {
                    throw new NotFound("widget");
                })
                .get("/mapped", context -> {
                    throw new IllegalArgumentException("bad value");
                })
                .get("/failure", context -> {
                    throw new IllegalStateException("database password is secret");
                })
                .exception(RuntimeException.class,
                        (exception, context) -> context.status(409).json(Map.of("error", "runtime")))
                .exception(IllegalArgumentException.class,
                        (exception, context) -> context.status(422).json(Map.of("error", "invalid")))) {

            var missing = VoltTest.request(app).get("/missing");
            var mapped = VoltTest.request(app).get("/mapped");
            var failure = VoltTest.request(app).get("/failure");

            assertEquals(404, missing.status());
            assertEquals("{\"error\":\"widget not found\"}", missing.bodyString());
            assertEquals(422, mapped.status());
            assertEquals("{\"error\":\"invalid\"}", mapped.bodyString());
            assertEquals(409, failure.status());
            assertEquals("{\"error\":\"runtime\"}", failure.bodyString());
            assertFalse(failure.bodyString().contains("password"));
        }
    }

    @Test
    void unexpectedFailuresUseGeneric500WithoutLeakingDetails() {
        try (var app = app().get("/failure", context -> {
            throw new AssertionError("database password is secret");
        })) {

            var failure = VoltTest.request(app).get("/failure");

            assertEquals(500, failure.status());
            assertEquals("{\"error\":\"internal server error\"}", failure.bodyString());
            assertFalse(failure.bodyString().contains("password"));
        }
    }

    @Test
    void middlewareIsOrderedPathScopedAndCanShortCircuit() {
        var events = new ArrayList<String>();
        var privateHandlerInvoked = new AtomicBoolean();
        try (var app = app()
                .before(context -> events.add("global-before"))
                .before("/api/*", context -> events.add("api-before"))
                .before("/private/*", context -> context.status(403).text("blocked"))
                .after("/private/*", context -> events.add("private-after"))
                .after("/api/*", context -> {
                    events.add("api-after-1");
                    context.header("X-After-One", "yes");
                })
                .after("/api/*", context -> {
                    events.add("api-after-2");
                    context.header("X-After-Two", "yes");
                })
                .get("/api/items", context -> {
                    events.add("handler");
                    context.text("items");
                })
                .get("/private/data", context -> {
                    privateHandlerInvoked.set(true);
                    context.text("secret");
                })) {

            var api = VoltTest.request(app).get("/api/items");
            var blocked = VoltTest.request(app).get("/private/data");

            assertEquals(
                    List.of("global-before", "api-before", "handler", "api-after-1", "api-after-2",
                            "global-before", "private-after"),
                    events);
            assertEquals("yes", api.header("X-After-One"));
            assertEquals("yes", api.header("X-After-Two"));
            assertEquals(403, blocked.status());
            assertEquals("blocked", blocked.bodyString());
            assertFalse(privateHandlerInvoked.get());
        }
    }

    @Test
    void corsHandlesSimpleCredentialedAndPreflightRequests() {
        Cors cors = Cors.allowAll()
                .allowMethods("GET", "POST")
                .allowHeaders("X-Token")
                .exposeHeaders("X-Request-Id")
                .allowCredentials()
                .maxAge(Duration.ofMinutes(10));
        try (var app = app().cors(cors).get("/data", context -> context.text("ok"))) {

            var simple = VoltTest.request(app)
                    .header("Origin", "https://app.example.com")
                    .get("/data");
            var otherOrigin = VoltTest.request(app)
                    .header("Origin", "https://other.example.com")
                    .get("/data");
            var preflight = VoltTest.request(app)
                    .header("Origin", "https://app.example.com")
                    .header("Access-Control-Request-Method", "POST")
                    .header("Access-Control-Request-Headers", "X-Token")
                    .options("/data");

            assertEquals("https://app.example.com", simple.header("Access-Control-Allow-Origin"));
            assertEquals("true", simple.header("Access-Control-Allow-Credentials"));
            assertEquals("X-Request-Id", simple.header("Access-Control-Expose-Headers"));
            assertEquals("https://other.example.com", otherOrigin.header("Access-Control-Allow-Origin"));
            assertEquals(204, preflight.status());
            assertTrue(preflight.header("Access-Control-Allow-Methods").contains("POST"));
            assertEquals("X-Token", preflight.header("Access-Control-Allow-Headers"));
            assertEquals("600", preflight.header("Access-Control-Max-Age"));
        }
    }

    @Test
    void corsOmitsHeadersForOriginsOutsideTheAllowList() {
        try (var app = app()
                .cors(Cors.allowOrigin("https://allowed.example.com"))
                .get("/", context -> context.text("ok"))) {

            var response = VoltTest.request(app)
                    .header("Origin", "https://other.example.com")
                    .get("/");

            assertEquals(200, response.status());
            assertNull(response.header("Access-Control-Allow-Origin"));
        }
    }

    @Test
    void corsHeadersRemainAvailableOnErrorResponses() {
        try (var app = app()
                .cors(Cors.allowOrigin("https://app.example.com"))
                .get("/", context -> {
                    throw new BadRequest("invalid request");
                })) {

            var response = VoltTest.request(app)
                    .header("Origin", "https://app.example.com")
                    .get("/");

            assertEquals(400, response.status());
            assertEquals(
                    "https://app.example.com",
                    response.header("Access-Control-Allow-Origin"));
        }
    }

    @Test
    void configuredHeaderLimitsReturn431() {
        try (var app = app()
                .maxRequestHeaders(100, 160)
                .get("/", context -> context.text("ok"))) {

            var response = VoltTest.request(app)
                    .header("X-Large", "x".repeat(512))
                    .get("/");

            assertEquals(431, response.status());
            assertEquals(
                    "{\"error\":\"request headers exceed configured limits\"}",
                    response.bodyString());
        }
    }

    @Test
    void testClientDoesNotFollowRedirectsAndDefensivelyCopiesBodies() {
        try (var app = app()
                .get("/redirect", context -> context.redirect("/target"))
                .get("/bytes", context -> context.bytes(new byte[]{1, 2}, "application/octet-stream"))) {

            var response = VoltTest.request(app).get("/redirect");
            var bytes = VoltTest.request(app).get("/bytes");
            byte[] body = bytes.bodyBytes();
            body[0] = 9;

            assertEquals(302, response.status());
            assertEquals("/target", response.header("Location"));
            assertEquals("", response.bodyString());
            assertNull(response.header("X-Missing"));
            assertThrows(IllegalArgumentException.class, () -> response.header("Bad Header"));
            assertThrows(UnsupportedOperationException.class,
                    () -> response.headers().put("X-Test", List.of("value")));
            assertEquals(1, bytes.bodyBytes()[0]);
        }
    }

    @Test
    void testClientValidatesRequestsAndEnforcesTimeouts() {
        try (var app = app().get("/slow", context -> {
            Thread.sleep(1_000);
            context.text("late");
        })) {

            assertThrows(IllegalArgumentException.class,
                    () -> VoltTest.request(app).header("Bad Header", "value"));
            assertThrows(IllegalArgumentException.class,
                    () -> VoltTest.request(app).header("X-Tést", "value"));
            assertThrows(IllegalArgumentException.class,
                    () -> VoltTest.request(app).send("GÉT", "/slow"));
            assertThrows(IllegalArgumentException.class,
                    () -> VoltTest.request(app).get("//example.com/path"));
            assertThrows(IllegalStateException.class,
                    () -> VoltTest.request(app)
                            .timeout(Duration.ofMillis(25))
                            .get("/slow"));
        }
    }

    private Volt app() {
        return Volt.port(0).shutdownHook(false);
    }

    private record Person(String name, int age) {
    }
}
