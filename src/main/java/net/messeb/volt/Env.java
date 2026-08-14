package net.messeb.volt;

import java.util.Locale;

public final class Env {
    private Env() {
    }

    public static String get(String name) {
        String value = value(name);
        if (value == null) {
            throw new IllegalStateException("missing environment variable: " + name);
        }
        return value;
    }

    public static String get(String name, String fallback) {
        String value = value(name);
        return value == null ? fallback : value;
    }

    public static int get(String name, int fallback) {
        String value = value(name);
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw invalid(name, "integer", exception);
        }
    }

    public static long get(String name, long fallback) {
        String value = value(name);
        if (value == null) {
            return fallback;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw invalid(name, "long", exception);
        }
    }

    public static boolean get(String name, boolean fallback) {
        String value = value(name);
        if (value == null) {
            return fallback;
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "true", "1", "yes", "on" -> true;
            case "false", "0", "no", "off" -> false;
            default -> throw invalid(name, "boolean", null);
        };
    }

    private static String value(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("environment variable name must not be blank");
        }
        if (name.indexOf('=') >= 0 || name.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("environment variable name contains an invalid character");
        }
        String value = System.getenv(name);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static IllegalArgumentException invalid(String name, String type, Exception cause) {
        return new IllegalArgumentException(
                "environment variable " + name + " is not a valid " + type,
                cause);
    }
}
