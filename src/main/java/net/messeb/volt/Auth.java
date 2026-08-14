package net.messeb.volt;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.function.Predicate;

public final class Auth implements RouteOption {
    public enum Source {
        HEADER,
        QUERY,
        BEARER,
        NONE
    }

    private static final Auth NONE = new Auth(Source.NONE, "", ignored -> true);

    private final Source source;
    private final String name;
    private final Predicate<String> validator;

    private Auth(Source source, String name, Predicate<String> validator) {
        this.source = Objects.requireNonNull(source, "source");
        this.name = Objects.requireNonNull(name, "name");
        this.validator = Objects.requireNonNull(validator, "validator");
    }

    public static Auth apiKey(Predicate<String> validator) {
        return apiKey("X-Api-Key", validator);
    }

    public static Auth apiKey(String headerName, Predicate<String> validator) {
        requireToken(headerName, "header name");
        return new Auth(Source.HEADER, headerName, validator);
    }

    public static Auth query(String parameterName, Predicate<String> validator) {
        if (parameterName == null || parameterName.isBlank()) {
            throw new IllegalArgumentException("query parameter name must not be blank");
        }
        return new Auth(Source.QUERY, parameterName, validator);
    }

    public static Auth bearer(Predicate<String> validator) {
        return new Auth(Source.BEARER, "", validator);
    }

    public static Auth none() {
        return NONE;
    }

    public Source source() {
        return source;
    }

    public String name() {
        return name;
    }

    @Override
    public Handler wrap(Handler next) {
        Objects.requireNonNull(next, "next");
        return context -> {
            authenticate(context);
            next.handle(context);
        };
    }

    @Override
    public String key() {
        return Auth.class.getName();
    }

    @Override
    public int order() {
        return -1_000;
    }

    boolean authenticate(Context context) {
        if (source == Source.NONE) {
            return true;
        }
        String key = switch (source) {
            case HEADER -> context.header(name);
            case QUERY -> context.query(name);
            case BEARER -> context.bearerToken();
            case NONE -> null;
        };
        if (key == null || !validator.test(key)) {
            throw new Unauthorized("invalid or missing API key", challenge());
        }
        context.attribute("apiKey", key);
        return true;
    }

    String challenge() {
        return switch (source) {
            case BEARER -> "Bearer realm=\"volt\"";
            case HEADER -> "ApiKey realm=\"volt\", header=\"" + name + "\"";
            case QUERY -> "ApiKey realm=\"volt\"";
            case NONE -> "";
        };
    }

    public static boolean constantTimeEquals(String first, String second) {
        byte[] firstHash = hash(first);
        byte[] secondHash = hash(second);
        boolean bothPresent = first != null & second != null;
        return bothPresent & MessageDigest.isEqual(firstHash, secondHash);
    }

    public static String mask(String key) {
        if (key == null || key.isEmpty()) {
            return "***";
        }
        int separator = key.indexOf('-');
        int visible = separator > 0 && separator < 5
                ? separator + 1
                : Math.min(3, key.length());
        return key.substring(0, visible) + "***";
    }

    @Override
    public String toString() {
        return source == Source.NONE
                ? "Auth.none"
                : "Auth[" + source.name().toLowerCase(java.util.Locale.ROOT)
                        + (name.isEmpty() ? "" : "=" + name) + "]";
    }

    private static byte[] hash(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            String checked = value == null ? "" : value;
            for (int index = 0; index < checked.length(); index++) {
                char character = checked.charAt(index);
                digest.update((byte) (character >>> 8));
                digest.update((byte) character);
            }
            return digest.digest();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
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
