package net.messeb.volt;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.StringJoiner;

public final class Cors {
    private static final Set<String> DEFAULT_METHODS =
            orderedSet("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS");

    private final Set<String> origins;
    private final Set<String> methods;
    private final Set<String> allowedHeaders;
    private final Set<String> exposedHeaders;
    private final boolean credentials;
    private final long maxAgeSeconds;

    private Cors(
            Set<String> origins,
            Set<String> methods,
            Set<String> allowedHeaders,
            Set<String> exposedHeaders,
            boolean credentials,
            long maxAgeSeconds) {
        this.origins = immutableOrdered(origins);
        this.methods = immutableOrdered(methods);
        this.allowedHeaders = immutableOrdered(allowedHeaders);
        this.exposedHeaders = immutableOrdered(exposedHeaders);
        this.credentials = credentials;
        this.maxAgeSeconds = maxAgeSeconds;
    }

    public static Cors allowAll() {
        return new Cors(Set.of("*"), DEFAULT_METHODS, Set.of("*"), Set.of(), false, 0);
    }

    public static Cors allowOrigin(String origin) {
        return allowOrigins(origin);
    }

    public static Cors allowOrigins(String... origins) {
        Set<String> checkedOrigins = values("origin", origins);
        checkedOrigins.forEach(Cors::requireOrigin);
        if (checkedOrigins.contains("*") && checkedOrigins.size() > 1) {
            throw new IllegalArgumentException("wildcard CORS origin must be configured alone");
        }
        return new Cors(checkedOrigins, DEFAULT_METHODS, Set.of(), Set.of(), false, 0);
    }

    public Cors allowMethods(String... methods) {
        var normalized = new LinkedHashSet<String>();
        for (String method : values("method", methods)) {
            requireToken(method, "method");
            normalized.add(method.toUpperCase(Locale.ROOT));
        }
        return copy(origins, normalized, allowedHeaders, exposedHeaders, credentials, maxAgeSeconds);
    }

    public Cors allowHeaders(String... headers) {
        var normalized = new LinkedHashSet<String>();
        for (String header : values("header", headers)) {
            if (!header.equals("*")) {
                requireToken(header, "header");
            }
            normalized.add(header);
        }
        return copy(origins, methods, normalized, exposedHeaders, credentials, maxAgeSeconds);
    }

    public Cors exposeHeaders(String... headers) {
        var normalized = new LinkedHashSet<String>();
        for (String header : values("header", headers)) {
            requireToken(header, "header");
            normalized.add(header);
        }
        return copy(origins, methods, allowedHeaders, normalized, credentials, maxAgeSeconds);
    }

    public Cors allowCredentials() {
        return copy(origins, methods, allowedHeaders, exposedHeaders, true, maxAgeSeconds);
    }

    public Cors maxAge(Duration duration) {
        if (duration == null || duration.isNegative()) {
            throw new IllegalArgumentException("CORS max age must not be negative");
        }
        return copy(
                origins, methods, allowedHeaders, exposedHeaders, credentials, duration.toSeconds());
    }

    void apply(Context context) {
        if (!applyHeaders(context)) {
            return;
        }
        if ("OPTIONS".equals(context.method())
                && context.header("Access-Control-Request-Method") != null) {
            requireRequestedToken(
                    context.header("Access-Control-Request-Method"),
                    "invalid Access-Control-Request-Method");
            context.header("Access-Control-Allow-Methods", join(methods));
            context.addHeader("Vary", "Access-Control-Request-Method");
            if (!allowedHeaders.isEmpty()) {
                String requested = context.header("Access-Control-Request-Headers");
                String checkedRequested = requested == null ? null : requestedHeaders(requested);
                context.header(
                        "Access-Control-Allow-Headers",
                        allowedHeaders.contains("*") && checkedRequested != null
                                ? checkedRequested
                                : join(allowedHeaders));
                if (requested != null) {
                    context.addHeader("Vary", "Access-Control-Request-Headers");
                }
            }
            if (maxAgeSeconds > 0) {
                context.header("Access-Control-Max-Age", Long.toString(maxAgeSeconds));
            }
            context.status(204).empty();
        }
    }

    void applyErrorHeaders(Context context) {
        applyHeaders(context);
    }

    private boolean applyHeaders(Context context) {
        String origin = context.header("Origin");
        if (origin == null || !allows(origin)) {
            return false;
        }
        boolean wildcard = origins.contains("*") && !credentials;
        context.header("Access-Control-Allow-Origin", wildcard ? "*" : origin);
        if (!wildcard) {
            context.addHeader("Vary", "Origin");
        }
        if (credentials) {
            context.header("Access-Control-Allow-Credentials", "true");
        }
        if (!exposedHeaders.isEmpty()) {
            context.header("Access-Control-Expose-Headers", join(exposedHeaders));
        }
        return true;
    }

    private boolean allows(String origin) {
        return origins.contains("*") || origins.contains(origin);
    }

    private Cors copy(
            Set<String> newOrigins,
            Set<String> newMethods,
            Set<String> newAllowedHeaders,
            Set<String> newExposedHeaders,
            boolean newCredentials,
            long newMaxAgeSeconds) {
        return new Cors(
                newOrigins,
                newMethods,
                newAllowedHeaders,
                newExposedHeaders,
                newCredentials,
                newMaxAgeSeconds);
    }

    private static Set<String> values(String description, String... values) {
        if (values == null || values.length == 0) {
            throw new IllegalArgumentException(description + " list must not be empty");
        }
        var result = new LinkedHashSet<String>();
        Arrays.stream(values).forEach(value -> {
            if (value == null || value.isBlank()
                    || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
                throw new IllegalArgumentException(description + " must be a single non-blank line");
            }
            result.add(value.trim());
        });
        return result;
    }

    private static String join(Set<String> values) {
        var result = new StringJoiner(", ");
        values.forEach(result::add);
        return result.toString();
    }

    private static String requestedHeaders(String value) {
        var result = new StringJoiner(", ");
        for (String header : value.split(",", -1)) {
            String normalized = header.trim();
            if (normalized.isEmpty()) {
                throw new BadRequest("invalid Access-Control-Request-Headers");
            }
            requireRequestedToken(normalized, "invalid Access-Control-Request-Headers");
            result.add(normalized);
        }
        return result.toString();
    }

    private static void requireRequestedToken(String value, String message) {
        try {
            requireToken(value, "CORS request value");
        } catch (IllegalArgumentException exception) {
            var badRequest = new BadRequest(message);
            badRequest.initCause(exception);
            throw badRequest;
        }
    }

    private static void requireOrigin(String value) {
        if (value.equals("*") || value.equals("null")) {
            return;
        }
        if (!value.chars().allMatch(character -> character >= 0x21 && character <= 0x7e)) {
            throw new IllegalArgumentException("origin is not a valid serialized origin");
        }
        try {
            URI origin = URI.create(value);
            if (!origin.isAbsolute()
                    || origin.getRawAuthority() == null
                    || origin.getRawAuthority().isEmpty()
                    || origin.getHost() == null
                    || origin.getRawUserInfo() != null
                    || (origin.getRawPath() != null && !origin.getRawPath().isEmpty())
                    || origin.getRawQuery() != null
                    || origin.getRawFragment() != null) {
                throw new IllegalArgumentException("origin is not a valid serialized origin");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("origin is not a valid serialized origin", exception);
        }
    }

    private static Set<String> orderedSet(String... values) {
        return immutableOrdered(new LinkedHashSet<>(Arrays.asList(values)));
    }

    private static Set<String> immutableOrdered(Set<String> values) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(values));
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
