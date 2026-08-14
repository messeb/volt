package net.messeb.volt;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HttpExceptionTest {
    @Test
    void exposesSafeStatusMessageAndImmutableHeaders() {
        var source = new java.util.LinkedHashMap<>(Map.of("Retry-After", "5"));
        var exception = new HttpException(429, "slow down", source);
        source.put("X-Changed", "yes");

        assertEquals(429, exception.status());
        assertEquals("slow down", exception.getMessage());
        assertEquals(Map.of("Retry-After", "5"), exception.headers());
        assertThrows(UnsupportedOperationException.class,
                () -> exception.headers().put("X-Test", "value"));
    }

    @Test
    void unauthorizedIncludesChallengeAndRejectsHeaderInjection() {
        var exception = new Unauthorized("invalid key", "Bearer realm=\"volt\"");

        assertEquals(401, exception.status());
        assertEquals("Bearer realm=\"volt\"", exception.headers().get("WWW-Authenticate"));
        assertThrows(IllegalArgumentException.class,
                () -> new Unauthorized("invalid", "Bearer\r\nX-Evil: yes"));
    }

    @Test
    void methodNotAllowedNormalizesAndDeduplicatesAllowHeader() {
        var exception = new MethodNotAllowed(List.of("get", "POST", "get"));

        assertEquals(405, exception.status());
        assertEquals("GET, POST", exception.headers().get("Allow"));
    }

    @Test
    void validatesStatusAndMessageAtConstructionBoundary() {
        assertThrows(IllegalArgumentException.class, () -> new HttpException(200, "not an error"));
        assertThrows(IllegalArgumentException.class, () -> new HttpException(400, " "));
        assertThrows(NullPointerException.class, () -> new HttpException(400, "bad", null));
    }

    @Test
    void rejectsUnsafeExceptionHeadersAndAllowMethods() {
        assertThrows(IllegalArgumentException.class,
                () -> new HttpException(400, "bad", Map.of("Bad Header", "value")));
        assertThrows(IllegalArgumentException.class,
                () -> new HttpException(400, "bad", Map.of("X-Test", "ok\r\nX-Evil: yes")));
        assertThrows(IllegalArgumentException.class,
                () -> new HttpException(400, "bad", Map.of("X-Tést", "value")));
        assertThrows(IllegalArgumentException.class,
                () -> new HttpException(
                        400, "bad", Map.of("Retry-After", "1", "retry-after", "2")));
        assertThrows(IllegalArgumentException.class,
                () -> new MethodNotAllowed(List.of("GÉT")));
        assertThrows(IllegalArgumentException.class,
                () -> new MethodNotAllowed(List.of("ß")));
        assertThrows(IllegalArgumentException.class,
                () -> new MethodNotAllowed(List.of("GET\r\nX-Evil")));
        assertThrows(IllegalArgumentException.class,
                () -> new Unauthorized("invalid", "Bearer\u0000token"));
    }
}
