package net.messeb.volt;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CorsTest {
    @Test
    void wildcardWithoutCredentialsDoesNotReflectTheOrigin() {
        try (var app = app()
                .cors(Cors.allowAll())
                .get("/", context -> context.text("ok"))) {

            var response = VoltTest.request(app)
                    .header("Origin", "https://app.example.com")
                    .get("/");

            assertEquals("*", response.header("Access-Control-Allow-Origin"));
            assertFalse(response.headerAll("Vary").contains("Origin"));
            assertNull(response.header("Access-Control-Allow-Credentials"));
        }
    }

    @Test
    void credentialedWildcardReflectsOriginAndVariesTheResponse() {
        try (var app = app()
                .cors(Cors.allowAll().allowCredentials())
                .get("/", context -> context.text("ok"))) {

            var response = VoltTest.request(app)
                    .header("Origin", "https://app.example.com")
                    .get("/");

            assertEquals("https://app.example.com", response.header("Access-Control-Allow-Origin"));
            assertEquals("true", response.header("Access-Control-Allow-Credentials"));
            assertTrue(response.headerAll("Vary").contains("Origin"));
        }
    }

    @Test
    void wildcardPreflightHeadersAreValidatedNormalizedAndVaried() {
        try (var app = app()
                .cors(Cors.allowAll().maxAge(Duration.ofSeconds(30)))
                .get("/", context -> context.text("ok"))) {

            var response = VoltTest.request(app)
                    .header("Origin", "https://app.example.com")
                    .header("Access-Control-Request-Method", "GET")
                    .header("Access-Control-Request-Headers", "X-One,  X-Two")
                    .options("/");

            assertEquals(204, response.status());
            assertEquals("X-One, X-Two", response.header("Access-Control-Allow-Headers"));
            assertEquals("30", response.header("Access-Control-Max-Age"));
            assertEquals(
                    List.of("Access-Control-Request-Method", "Access-Control-Request-Headers"),
                    response.headerAll("Vary"));
        }
    }

    @Test
    void malformedPreflightTokensBecomeSafeBadRequests() {
        try (var app = app()
                .cors(Cors.allowAll())
                .get("/", context -> context.text("ok"))) {

            var invalidMethod = VoltTest.request(app)
                    .header("Origin", "https://app.example.com")
                    .header("Access-Control-Request-Method", "GÉT")
                    .options("/");
            var invalidHeaders = VoltTest.request(app)
                    .header("Origin", "https://app.example.com")
                    .header("Access-Control-Request-Method", "GET")
                    .header("Access-Control-Request-Headers", "X-One,")
                    .options("/");
            var emptyMethod = VoltTest.request(app)
                    .header("Origin", "https://app.example.com")
                    .header("Access-Control-Request-Method", "")
                    .options("/");

            assertEquals(400, invalidMethod.status());
            assertEquals(
                    "{\"error\":\"invalid Access-Control-Request-Method\"}",
                    invalidMethod.bodyString());
            assertEquals(400, invalidHeaders.status());
            assertEquals(
                    "{\"error\":\"invalid Access-Control-Request-Headers\"}",
                    invalidHeaders.bodyString());
            assertEquals(400, emptyMethod.status());
            assertEquals("*", invalidHeaders.header("Access-Control-Allow-Origin"));
        }
    }

    @Test
    void configurationRejectsHeaderInjectionAndNonAsciiTokens() {
        assertThrows(IllegalArgumentException.class,
                () -> Cors.allowOrigin("https://example.com\r\nX-Evil: yes"));
        assertThrows(IllegalArgumentException.class,
                () -> Cors.allowOrigin("https://example.com/path"));
        assertThrows(IllegalArgumentException.class,
                () -> Cors.allowOrigin("https://user@example.com"));
        assertThrows(IllegalArgumentException.class,
                () -> Cors.allowOrigins("*", "https://example.com"));
        assertThrows(IllegalArgumentException.class,
                () -> Cors.allowAll().allowMethods("GÉT"));
        assertThrows(IllegalArgumentException.class,
                () -> Cors.allowAll().allowHeaders("Bad Header"));
        assertThrows(IllegalArgumentException.class,
                () -> Cors.allowAll().exposeHeaders("X-Test\nX-Evil"));
        assertThrows(IllegalArgumentException.class,
                () -> Cors.allowAll().maxAge(Duration.ofSeconds(-1)));
    }

    private Volt app() {
        return Volt.port(0).shutdownHook(false);
    }
}
