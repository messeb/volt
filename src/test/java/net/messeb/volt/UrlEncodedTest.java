package net.messeb.volt;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UrlEncodedTest {
    @Test
    void parsesRepeatedEmptyAndUnicodeValues() {
        var values = UrlEncoded.parse("tag=java&tag=web&empty=&flag&name=Ada+Lovelace&emoji=%F0%9F%9A%80");

        assertEquals(List.of("java", "web"), values.get("tag"));
        assertEquals(List.of(""), values.get("empty"));
        assertEquals(List.of(""), values.get("flag"));
        assertEquals(List.of("Ada Lovelace"), values.get("name"));
        assertEquals(List.of("🚀"), values.get("emoji"));
    }

    @Test
    void parsesUnicodeThatWasNotPercentEncoded() {
        assertEquals(List.of("Grüße"), UrlEncoded.parse("message=Grüße").get("message"));
    }

    @Test
    void rejectsMalformedPercentEncodingAndUtf8() {
        assertThrows(BadRequest.class, () -> UrlEncoded.parse("x=%"));
        assertThrows(BadRequest.class, () -> UrlEncoded.parse("x=%GG"));
        assertThrows(BadRequest.class, () -> UrlEncoded.parse("x=%C3%28"));
        assertThrows(BadRequest.class, () -> UrlEncoded.parse("x=\ud800"));
        assertThrows(BadRequest.class, () -> UrlEncoded.parse("x=\udc00"));
        assertThrows(IllegalArgumentException.class, () -> UrlEncoded.parse((byte[]) null));
    }
}
