package net.messeb.volt;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class UrlEncoded {
    private UrlEncoded() {
    }

    static Map<String, List<String>> parse(byte[] value) {
        if (value == null) {
            throw new IllegalArgumentException("value must not be null");
        }
        try {
            String decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(value))
                    .toString();
            return parse(decoded);
        } catch (CharacterCodingException exception) {
            throw new BadRequest("URL-encoded data is not valid UTF-8");
        }
    }

    static Map<String, List<String>> parse(String value) {
        var result = new LinkedHashMap<String, List<String>>();
        if (value == null || value.isEmpty()) {
            return result;
        }
        for (String pair : value.split("&", -1)) {
            int separator = pair.indexOf('=');
            String name = decode(separator < 0 ? pair : pair.substring(0, separator));
            String item = decode(separator < 0 ? "" : pair.substring(separator + 1));
            result.computeIfAbsent(name, ignored -> new ArrayList<>()).add(item);
        }
        return result;
    }

    private static String decode(String value) {
        var bytes = new ByteArrayOutputStream(value.length());
        for (int index = 0; index < value.length();) {
            char character = value.charAt(index);
            if (character == '+') {
                bytes.write(' ');
                index++;
            } else if (character == '%') {
                if (index + 2 >= value.length()) {
                    throw new BadRequest("malformed percent encoding");
                }
                int high = Character.digit(value.charAt(index + 1), 16);
                int low = Character.digit(value.charAt(index + 2), 16);
                if (high < 0 || low < 0) {
                    throw new BadRequest("malformed percent encoding");
                }
                bytes.write(high * 16 + low);
                index += 3;
            } else {
                if (Character.isLowSurrogate(character)
                        || Character.isHighSurrogate(character)
                        && (index + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(index + 1)))) {
                        throw new BadRequest("URL-encoded data is not valid UTF-8");
                }
                int length = Character.isHighSurrogate(character) ? 2 : 1;
                byte[] encoded = value.substring(index, index + length)
                        .getBytes(StandardCharsets.UTF_8);
                bytes.writeBytes(encoded);
                index += length;
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray()))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new BadRequest("URL-encoded data is not valid UTF-8");
        }
    }
}
