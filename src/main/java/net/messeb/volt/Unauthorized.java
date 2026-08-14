package net.messeb.volt;

import java.util.Map;

public final class Unauthorized extends HttpException {
    public Unauthorized(String message) {
        this(message, "ApiKey");
    }

    public Unauthorized(String message, String challenge) {
        super(401, message, Map.of("WWW-Authenticate", requireChallenge(challenge)));
    }

    private static String requireChallenge(String challenge) {
        if (challenge == null || challenge.isBlank()) {
            throw new IllegalArgumentException("authentication challenge must be a single non-blank line");
        }
        for (int index = 0; index < challenge.length(); index++) {
            char character = challenge.charAt(index);
            if (character == '\r' || character == '\n'
                    || character == 0x7f || character < 0x20 && character != '\t') {
                throw new IllegalArgumentException(
                        "authentication challenge must be a single non-blank line");
            }
        }
        return challenge;
    }
}
