package net.messeb.volt;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class VoltTest {
    private VoltTest() {
    }

    public static Request request(Volt volt) {
        return new Request(volt);
    }

    public static final class Request {
        private final Volt volt;
        private final Map<String, List<String>> headers = new LinkedHashMap<>();
        private byte[] body = new byte[0];
        private Duration timeout = Duration.ofSeconds(10);

        private Request(Volt volt) {
            this.volt = Objects.requireNonNull(volt, "volt");
        }

        public Request header(String name, String value) {
            validateHeader(name, value);
            headers.keySet().removeIf(existing -> existing.equalsIgnoreCase(name));
            headers.put(name, new ArrayList<>(List.of(value)));
            return this;
        }

        public Request addHeader(String name, String value) {
            validateHeader(name, value);
            String existing = headers.keySet().stream()
                    .filter(item -> item.equalsIgnoreCase(name))
                    .findFirst()
                    .orElse(name);
            headers.computeIfAbsent(existing, ignored -> new ArrayList<>()).add(value);
            return this;
        }

        public Request body(String value) {
            return body(Objects.requireNonNull(value, "value").getBytes(StandardCharsets.UTF_8));
        }

        public Request body(byte[] value) {
            body = Objects.requireNonNull(value, "value").clone();
            return this;
        }

        public Request json(Object value) {
            return json(volt.jsonCodecForTest(), value);
        }

        public Request json(JsonCodec codec, Object value) {
            byte[] encoded = Objects.requireNonNull(
                    Objects.requireNonNull(codec, "codec").encode(value),
                    "encoded JSON");
            body = encoded.clone();
            return header("Content-Type", "application/json");
        }

        public Request form(String value) {
            body(value);
            return header("Content-Type", "application/x-www-form-urlencoded");
        }

        public Request timeout(Duration value) {
            if (value == null || value.isZero() || value.isNegative()) {
                throw new IllegalArgumentException("request timeout must be positive");
            }
            timeout = value;
            return this;
        }

        public Response get(String path) {
            return send("GET", path);
        }

        public Response post(String path) {
            return send("POST", path);
        }

        public Response put(String path) {
            return send("PUT", path);
        }

        public Response delete(String path) {
            return send("DELETE", path);
        }

        public Response patch(String path) {
            return send("PATCH", path);
        }

        public Response head(String path) {
            return send("HEAD", path);
        }

        public Response options(String path) {
            return send("OPTIONS", path);
        }

        public Response send(String method, String path) {
            requireToken(method, "method");
            if (path == null || path.isEmpty() || path.charAt(0) != '/') {
                throw new IllegalArgumentException("request path must start with '/'");
            }
            URI uri;
            try {
                uri = URI.create(path);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("request path is not a valid URI", exception);
            }
            if (uri.isAbsolute() || uri.getRawAuthority() != null || uri.getRawFragment() != null) {
                throw new IllegalArgumentException("request path must be an origin-form URI");
            }
            var exchange = new InMemoryExchange(method, path, headers, body);
            var request = new FutureTask<>(() -> {
                volt.dispatchForTest(exchange);
                InMemoryExchange.Result result = exchange.result();
                return new Response(
                        result.status(),
                        result.headers(),
                        result.body(),
                        volt.jsonCodecForTest());
            });
            Thread.ofVirtual().name("volt-test-request").start(request);
            try {
                return request.get(timeoutNanos(), TimeUnit.NANOSECONDS);
            } catch (TimeoutException exception) {
                request.cancel(true);
                throw new IllegalStateException("test request timed out after " + timeout, exception);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("test request was interrupted", exception);
            } catch (ExecutionException exception) {
                Throwable cause = exception.getCause();
                if (cause instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                if (cause instanceof Error error) {
                    throw error;
                }
                throw new IllegalStateException("test request failed", cause);
            }
        }

        private long timeoutNanos() {
            try {
                return timeout.toNanos();
            } catch (ArithmeticException ignored) {
                return Long.MAX_VALUE;
            }
        }
    }

    public static final class Response {
        private final int status;
        private final Map<String, List<String>> headers;
        private final byte[] body;
        private final JsonCodec jsonCodec;

        private Response(
                int status,
                Map<String, List<String>> headers,
                byte[] body,
                JsonCodec jsonCodec) {
            this.status = status;
            var copy = new LinkedHashMap<String, List<String>>();
            headers.forEach((name, values) -> copy.put(name, List.copyOf(values)));
            this.headers = Collections.unmodifiableMap(copy);
            this.body = body.clone();
            this.jsonCodec = Objects.requireNonNull(jsonCodec, "jsonCodec");
        }

        public int status() {
            return status;
        }

        public String header(String name) {
            List<String> values = findHeader(name);
            return values == null || values.isEmpty() ? null : values.getFirst();
        }

        public List<String> headerAll(String name) {
            List<String> values = findHeader(name);
            return values == null ? List.of() : values;
        }

        public Map<String, List<String>> headers() {
            return headers;
        }

        public byte[] bodyBytes() {
            return body.clone();
        }

        public String bodyString() {
            return new String(body, StandardCharsets.UTF_8);
        }

        public <T> T body(Class<T> type) {
            return body(jsonCodec, type);
        }

        public <T> T body(JsonCodec codec, Class<T> type) {
            return Objects.requireNonNull(codec, "codec").decode(body, type);
        }

        private List<String> findHeader(String name) {
            requireToken(name, "header name");
            return headers.entrySet().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse(null);
        }
    }

    private static void validateHeader(String name, String value) {
        requireToken(name, "header name");
        Objects.requireNonNull(value, "header value");
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '\r' || character == '\n'
                    || character > 0xff || character == 0x7f
                    || character < 0x20 && character != '\t') {
                throw new IllegalArgumentException("header value contains an invalid character");
            }
        }
    }

    private static void requireToken(String value, String description) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(description + " must not be blank");
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            boolean token = character <= 0x7f
                    && (Character.isLetterOrDigit(character)
                    || "!#$%&'*+-.^_`|~".indexOf(character) >= 0);
            if (!token) {
                throw new IllegalArgumentException(description + " is not a valid HTTP token");
            }
        }
    }

}
