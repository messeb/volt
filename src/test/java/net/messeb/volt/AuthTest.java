package net.messeb.volt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthTest {
    @Test
    void constantTimeHelperComparesUnicodeKeysAndHandlesNull() {
        assertTrue(Auth.constantTimeEquals("sk-sëcret", "sk-sëcret"));
        assertFalse(Auth.constantTimeEquals("sk-secret", "sk-other"));
        assertFalse(Auth.constantTimeEquals("short", "a much longer key"));
        assertFalse(Auth.constantTimeEquals(null, null));
        assertFalse(Auth.constantTimeEquals("key", null));
        assertTrue(Auth.constantTimeEquals("\ud800", "\ud800"));
        assertFalse(Auth.constantTimeEquals("\ud800", "?"));
    }

    @Test
    void masksKeyMaterialWithoutExposingTheSecret() {
        assertTrue(Auth.mask("sk-secret-value").equals("sk-***"));
        assertTrue(Auth.mask("abcdef").equals("abc***"));
        assertTrue(Auth.mask(null).equals("***"));
    }

    @Test
    void rejectsInvalidSourceNames() {
        assertThrows(IllegalArgumentException.class, () -> Auth.apiKey("Bad Header", key -> true));
        assertThrows(IllegalArgumentException.class, () -> Auth.apiKey("X-Kéy", key -> true));
        assertThrows(IllegalArgumentException.class, () -> Auth.query(" ", key -> true));
        assertThrows(NullPointerException.class, () -> Auth.bearer(null));
        assertThrows(NullPointerException.class, () -> Auth.apiKey(null));
        assertThrows(NullPointerException.class, () -> Auth.query("api_key", null));
        assertDoesNotThrow(() -> Auth.apiKey("X-Api-Key", key -> true));
    }

    @Test
    void debugDescriptionNeverIncludesKeyValues() {
        String description = Auth.apiKey("X-Secret", key -> key.equals("do-not-print")).toString();

        assertTrue(description.contains("X-Secret"));
        assertFalse(description.contains("do-not-print"));
    }
}
