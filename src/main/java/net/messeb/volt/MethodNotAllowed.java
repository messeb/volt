package net.messeb.volt;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.StringJoiner;

public final class MethodNotAllowed extends HttpException {
    public MethodNotAllowed(Collection<String> allowedMethods) {
        this(join(allowedMethods));
    }

    private MethodNotAllowed(String allow) {
        super(405, "method not allowed", Map.of("Allow", allow));
    }

    private static String join(Collection<String> methods) {
        if (methods == null || methods.isEmpty()) {
            throw new IllegalArgumentException("allowed methods must not be empty");
        }
        var normalized = new LinkedHashSet<String>();
        for (String method : methods) {
            if (method == null || method.isBlank()) {
                throw new IllegalArgumentException("allowed method must not be blank");
            }
            String value = method.trim();
            requireToken(value);
            normalized.add(value.toUpperCase(java.util.Locale.ROOT));
        }
        var result = new StringJoiner(", ");
        normalized.forEach(result::add);
        return result.toString();
    }

    private static void requireToken(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            boolean token = character <= 0x7f
                    && (Character.isLetterOrDigit(character)
                    || "!#$%&'*+-.^_`|~".indexOf(character) >= 0);
            if (!token) {
                throw new IllegalArgumentException("allowed method is not a valid HTTP token");
            }
        }
    }
}
