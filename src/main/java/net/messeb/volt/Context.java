package net.messeb.volt;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

public final class Context {
    private static final byte[] EMPTY = new byte[0];

    private final HttpExchange exchange;
    private final long maxBodySize;
    private final JsonCodec jsonCodec;
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();
    private final Headers responseHeaders = new Headers();
    private volatile Map<String, String> pathParameters = Map.of();
    private volatile Map<String, List<String>> queryParameters;
    private volatile FutureTask<Map<String, List<String>>> formParametersTask;
    private volatile FutureTask<byte[]> requestBodyTask;
    private int status = 200;
    private byte[] responseBody;
    private boolean responseSelected;
    private boolean committed;

    Context(HttpExchange exchange, long maxBodySize, JsonCodec jsonCodec) {
        this.exchange = Objects.requireNonNull(exchange, "exchange");
        this.maxBodySize = maxBodySize;
        this.jsonCodec = Objects.requireNonNull(jsonCodec, "jsonCodec");
    }

    public String method() {
        return exchange.getRequestMethod();
    }

    public URI uri() {
        return exchange.getRequestURI();
    }

    public String requestPath() {
        return uri().getPath();
    }

    public InetSocketAddress remoteAddress() {
        return exchange.getRemoteAddress();
    }

    public InetSocketAddress localAddress() {
        return exchange.getLocalAddress();
    }

    public String path(String name) {
        requireName(name, "path parameter");
        return pathParameters.get(name);
    }

    public String pathRequired(String name) {
        String value = path(name);
        if (value == null) {
            throw new BadRequest("missing path parameter: " + name);
        }
        return value;
    }

    public String query(String name) {
        List<String> values = queryValues(name);
        return values.isEmpty() ? null : values.getFirst();
    }

    public String query(String name, String fallback) {
        String value = query(name);
        return value == null ? fallback : value;
    }

    public String queryRequired(String name) {
        String value = query(name);
        if (value == null) {
            throw new BadRequest("missing query parameter: " + name);
        }
        return value;
    }

    public List<String> queryAll(String name) {
        return queryValues(name);
    }

    public int queryInt(String name, int fallback) {
        String value = query(name);
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            var badRequest = new BadRequest("invalid integer query parameter: " + name);
            badRequest.initCause(exception);
            throw badRequest;
        }
    }

    public boolean queryBool(String name, boolean fallback) {
        String value = query(name);
        if (value == null) {
            return fallback;
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "true", "1", "yes", "on" -> true;
            case "false", "0", "no", "off" -> false;
            default -> throw new BadRequest("invalid boolean query parameter: " + name);
        };
    }

    public String header(String name) {
        validateHeaderName(name);
        return exchange.getRequestHeaders().getFirst(name);
    }

    public List<String> headerAll(String name) {
        validateHeaderName(name);
        List<String> values = exchange.getRequestHeaders().get(name);
        return values == null ? List.of() : List.copyOf(values);
    }

    public String headerRequired(String name) {
        String value = header(name);
        if (value == null) {
            throw new BadRequest("missing request header: " + name);
        }
        return value;
    }

    public Map<String, List<String>> headers() {
        var copy = new LinkedHashMap<String, List<String>>();
        exchange.getRequestHeaders().forEach((name, values) -> copy.put(name, List.copyOf(values)));
        return Collections.unmodifiableMap(copy);
    }

    public String contentType() {
        return header("Content-Type");
    }

    public String userAgent() {
        return header("User-Agent");
    }

    public String bearerToken() {
        String authorization = header("Authorization");
        if (authorization == null || authorization.length() < 8
                || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String token = authorization.substring(7).trim();
        if (token.isEmpty() || token.chars().anyMatch(Character::isWhitespace)) {
            return null;
        }
        return token;
    }

    public byte[] bodyBytes() {
        FutureTask<byte[]> task = requestBodyTask;
        if (task == null) {
            synchronized (this) {
                task = requestBodyTask;
                if (task == null) {
                    task = new FutureTask<>(() -> {
                        long contentLength = contentLength();
                        try {
                            return BodyReader.read(
                                    exchange.getRequestBody(),
                                    contentLength,
                                    maxBodySize);
                        } catch (IOException exception) {
                            throw new IllegalStateException("failed to read request body", exception);
                        }
                    });
                    requestBodyTask = task;
                }
            }
        }
        task.run();
        return await(task, "request body read").clone();
    }

    public String bodyString() {
        return new String(bodyBytes(), StandardCharsets.UTF_8);
    }

    public <T> T body(Class<T> type) {
        Objects.requireNonNull(type, "type");
        try {
            return jsonCodec.decode(bodyBytes(), type);
        } catch (JsonException | IllegalArgumentException exception) {
            var badRequest = new BadRequest("malformed JSON request body");
            badRequest.initCause(exception);
            throw badRequest;
        }
    }

    public String formParam(String name) {
        requireName(name, "form parameter");
        List<String> values = formParameters().get(name);
        return values == null || values.isEmpty() ? null : values.getFirst();
    }

    public List<String> formParams(String name) {
        requireName(name, "form parameter");
        List<String> values = formParameters().get(name);
        return values == null ? List.of() : values;
    }

    @SuppressWarnings("unchecked")
    public <T> T attribute(String name) {
        requireName(name, "attribute");
        return (T) attributes.get(name);
    }

    public Context attribute(String name, Object value) {
        requireName(name, "attribute");
        if (value == null) {
            attributes.remove(name);
        } else {
            attributes.put(name, value);
        }
        return this;
    }

    public int status() {
        return status;
    }

    public Context status(int status) {
        ensureMutable();
        if (status < 200 || status > 599) {
            throw new IllegalArgumentException("response status must be between 200 and 599");
        }
        if (bodyForbidden(status) && responseSelected && responseBody.length > 0) {
            throw new IllegalStateException("status " + status + " must not include a response body");
        }
        this.status = status;
        return this;
    }

    public Context header(String name, String value) {
        ensureMutable();
        validateHeader(name, value);
        responseHeaders.set(name, value);
        return this;
    }

    public Context addHeader(String name, String value) {
        ensureMutable();
        validateHeader(name, value);
        responseHeaders.add(name, value);
        return this;
    }

    public Context text(String value) {
        return response(Objects.requireNonNull(value, "value").getBytes(StandardCharsets.UTF_8),
                "text/plain; charset=utf-8");
    }

    public Context html(String value) {
        return response(Objects.requireNonNull(value, "value").getBytes(StandardCharsets.UTF_8),
                "text/html; charset=utf-8");
    }

    public Context json(Object value) {
        byte[] encoded = Objects.requireNonNull(jsonCodec.encode(value), "encoded JSON");
        return response(encoded.clone(), "application/json; charset=utf-8");
    }

    public Context bytes(byte[] value, String contentType) {
        Objects.requireNonNull(value, "value");
        validateHeader("Content-Type", contentType);
        return response(value.clone(), contentType);
    }

    public Context empty() {
        return response(EMPTY, null);
    }

    public Context redirect(String location) {
        return redirect(location, 302);
    }

    public Context redirect(String location, int status) {
        if (status < 300 || status > 399) {
            throw new IllegalArgumentException("redirect status must be between 300 and 399");
        }
        return status(status).header("Location", Objects.requireNonNull(location, "location")).empty();
    }

    public boolean responded() {
        return responseSelected;
    }

    void pathParameters(Map<String, String> values) {
        pathParameters = Map.copyOf(values);
    }

    void resetResponse() {
        ensureMutable();
        status = 200;
        responseHeaders.clear();
        responseBody = null;
        responseSelected = false;
    }

    void errorJson(String message) {
        response(
                SimpleJsonCodec.INSTANCE.encode(Map.of("error", message)),
                "application/json; charset=utf-8");
    }

    void commit() throws IOException {
        if (committed) {
            throw new IllegalStateException("response is already committed");
        }
        byte[] body = responseSelected ? responseBody : EMPTY;
        boolean bodyForbidden = bodyForbidden(status);
        if (bodyForbidden && body.length != 0) {
            throw new IllegalStateException("status " + status + " must not include a response body");
        }

        boolean head = method().equalsIgnoreCase("HEAD");
        try {
            Headers target = exchange.getResponseHeaders();
            target.putAll(responseHeaders);
            if (status == 204 || status == 304) {
                target.remove("Content-Length");
            } else {
                target.set("Content-Length", Integer.toString(body.length));
            }
            committed = true;
            exchange.sendResponseHeaders(
                    status,
                    bodyForbidden || head || body.length == 0 ? -1 : body.length);
            if (!head && !bodyForbidden && body.length > 0) {
                exchange.getResponseBody().write(body);
            }
        } finally {
            exchange.close();
        }
    }

    private Context response(byte[] body, String defaultContentType) {
        ensureMutable();
        if (responseSelected) {
            throw new IllegalStateException("a response body has already been selected");
        }
        if (bodyForbidden(status) && body.length > 0) {
            throw new IllegalStateException("status " + status + " must not include a response body");
        }
        if (defaultContentType != null && responseHeaders.getFirst("Content-Type") == null) {
            responseHeaders.set("Content-Type", defaultContentType);
        }
        responseBody = body;
        responseSelected = true;
        return this;
    }

    private List<String> queryValues(String name) {
        requireName(name, "query parameter");
        List<String> values = queryParameters().get(name);
        return values == null ? List.of() : values;
    }

    private Map<String, List<String>> queryParameters() {
        Map<String, List<String>> values = queryParameters;
        if (values == null) {
            synchronized (this) {
                values = queryParameters;
                if (values == null) {
                    values = immutableParameters(UrlEncoded.parse(uri().getRawQuery()));
                    queryParameters = values;
                }
            }
        }
        return values;
    }

    private Map<String, List<String>> formParameters() {
        FutureTask<Map<String, List<String>>> task = formParametersTask;
        if (task == null) {
            synchronized (this) {
                task = formParametersTask;
                if (task == null) {
                    task = new FutureTask<>(this::parseFormParameters);
                    formParametersTask = task;
                }
            }
        }
        task.run();
        return await(task, "form data parsing");
    }

    private Map<String, List<String>> parseFormParameters() {
        String type = contentType();
        String mediaType = type == null ? null : type.split(";", 2)[0].trim();
        return !"application/x-www-form-urlencoded".equalsIgnoreCase(mediaType)
                ? Map.of()
                : immutableParameters(UrlEncoded.parse(bodyBytes()));
    }

    private Map<String, List<String>> immutableParameters(Map<String, List<String>> values) {
        var copy = new LinkedHashMap<String, List<String>>();
        values.forEach((name, items) -> copy.put(name, List.copyOf(new ArrayList<>(items))));
        return Collections.unmodifiableMap(copy);
    }

    private long contentLength() {
        List<String> fields = headerAll("Content-Length");
        if (fields.isEmpty()) {
            return -1;
        }
        long result = -1;
        for (String field : fields) {
            for (String value : field.split(",", -1)) {
                String normalized = value.trim();
                if (normalized.isEmpty()
                        || normalized.chars().anyMatch(character -> character < '0' || character > '9')) {
                    throw new BadRequest("invalid Content-Length");
                }
                long length;
                try {
                    length = Long.parseLong(normalized);
                } catch (NumberFormatException exception) {
                    var badRequest = new BadRequest("invalid Content-Length");
                    badRequest.initCause(exception);
                    throw badRequest;
                }
                if (result >= 0 && result != length) {
                    throw new BadRequest("conflicting Content-Length headers");
                }
                result = length;
            }
        }
        return result;
    }

    private void ensureMutable() {
        if (committed) {
            throw new IllegalStateException("response is already committed");
        }
    }

    private static <T> T await(FutureTask<T> task, String operation) {
        try {
            return task.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(operation + " was interrupted", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(operation + " failed", cause);
        }
    }

    private static void requireName(String value, String subject) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(subject + " must not be blank");
        }
    }

    private static void validateHeader(String name, String value) {
        validateHeaderName(name);
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

    private static void validateHeaderName(String name) {
        requireName(name, "header name");
        for (int index = 0; index < name.length(); index++) {
            char character = name.charAt(index);
            boolean token = character <= 0x7f && (Character.isLetterOrDigit(character)
                    || "!#$%&'*+-.^_`|~".indexOf(character) >= 0);
            if (!token) {
                throw new IllegalArgumentException("header name is not a valid HTTP token");
            }
        }
    }

    private static boolean bodyForbidden(int status) {
        return status == 204 || status == 205 || status == 304;
    }
}
