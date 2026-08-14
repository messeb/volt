package net.messeb.volt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EnvTest {
    private static final String ABSENT = "VOLT_TEST_VARIABLE_THAT_SHOULD_NOT_EXIST_7E63388B";

    @Test
    void returnsTypedFallbacksForMissingVariables() {
        assertEquals("fallback", Env.get(ABSENT, "fallback"));
        assertEquals(8080, Env.get(ABSENT, 8080));
        assertEquals(12L, Env.get(ABSENT, 12L));
        assertEquals(true, Env.get(ABSENT, true));
    }

    @Test
    void requiredLookupFailsClearlyWhenMissing() {
        var exception = assertThrows(IllegalStateException.class, () -> Env.get(ABSENT));

        assertEquals("missing environment variable: " + ABSENT, exception.getMessage());
    }

    @Test
    void rejectsBlankVariableNames() {
        assertThrows(IllegalArgumentException.class, () -> Env.get(" ", "fallback"));
        assertThrows(IllegalArgumentException.class, () -> Env.get("BAD=NAME", "fallback"));
        assertThrows(IllegalArgumentException.class, () -> Env.get("BAD\0NAME", "fallback"));
    }
}
