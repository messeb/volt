package net.messeb.volt;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public class HttpException extends RuntimeException {
    private final int status;
    private final Map<String, String> headers;

    public HttpException(int status, String message) {
        this(status, message, Map.of());
    }

    public HttpException(int status, String message, Map<String, String> headers) {
        super(requireMessage(message));
        if (status < 400 || status > 599) {
            throw new IllegalArgumentException("HTTP exception status must be between 400 and 599");
        }
        this.status = status;
        var checkedHeaders = new LinkedHashMap<String, String>();
        Objects.requireNonNull(headers, "headers").forEach((name, value) -> {
            validateHeader(name, value);
            if (checkedHeaders.keySet().stream()
                    .anyMatch(existing -> existing.equalsIgnoreCase(name))) {
                throw new IllegalArgumentException(
                        "duplicate HTTP exception header name: " + name);
            }
            checkedHeaders.put(name, value);
        });
        this.headers = Collections.unmodifiableMap(checkedHeaders);
    }

    public int status() {
        return status;
    }

    public Map<String, String> headers() {
        return headers;
    }

    private static String requireMessage(String message) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("HTTP exception message must not be blank");
        }
        return message;
    }

    private static void validateHeader(String name, String value) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("HTTP exception header name must not be blank");
        }
        for (int index = 0; index < name.length(); index++) {
            char character = name.charAt(index);
            boolean token = character <= 0x7f
                    && (Character.isLetterOrDigit(character)
                    || "!#$%&'*+-.^_`|~".indexOf(character) >= 0);
            if (!token) {
                throw new IllegalArgumentException(
                        "HTTP exception header name is not a valid HTTP token");
            }
        }
        Objects.requireNonNull(value, "HTTP exception header value");
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '\r' || character == '\n'
                    || character == 0x7f || character < 0x20 && character != '\t') {
                throw new IllegalArgumentException(
                        "HTTP exception header value contains an invalid character");
            }
        }
    }
}
