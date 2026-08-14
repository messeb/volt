package net.messeb.volt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.SortedMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimpleJsonCodecTest {
    private final JsonCodec codec = JsonCodec.defaultCodec();

    @Test
    void roundTripsRecordsAndGenericCollections() {
        var person = new Person("Ada\nLovelace", 36, List.of("math", "code"));

        byte[] encoded = codec.encode(person);
        Person decoded = codec.decode(encoded, Person.class);

        assertEquals(person, decoded);
        assertEquals(
                "{\"name\":\"Ada\\nLovelace\",\"age\":36,\"tags\":[\"math\",\"code\"]}",
                new String(encoded, StandardCharsets.UTF_8));
    }

    @Test
    void decodesSetAndMapRecordComponents() {
        var payload = codec.decode(
                "{\"roles\":[\"admin\",\"writer\",\"admin\"],\"scores\":{\"math\":10}}"
                        .getBytes(StandardCharsets.UTF_8),
                CollectionsPayload.class);

        assertEquals(Set.of("admin", "writer"), payload.roles());
        assertEquals(Map.of("math", 10), payload.scores());
    }

    @Test
    void encodesMapsArraysEnumsAndNulls() {
        byte[] encoded = codec.encode(Map.of(
                "enabled", true,
                "values", new Object[]{1, null, State.READY}));

        Object decoded = codec.decode(encoded, Object.class);

        var expected = new java.util.LinkedHashMap<String, Object>();
        expected.put("enabled", true);
        expected.put("values", java.util.Arrays.asList(
                new java.math.BigDecimal("1"), null, "READY"));
        assertEquals(expected, decoded);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "{",
            "[1,]",
            "{\"x\":1,}",
            "{\"x\":null,\"x\":2}",
            "{\"x\":01}",
            "\"\\q\"",
            "\"\\uD800\"",
            "\"\\uDC00\"",
            "true false"
    })
    void rejectsMalformedJson(String json) {
        assertThrows(JsonException.class,
                () -> codec.decode(json.getBytes(StandardCharsets.UTF_8), Object.class));
    }

    @Test
    void rejectsMalformedUtf8() {
        assertThrows(JsonException.class,
                () -> codec.decode(new byte[]{(byte) 0xc3, 0x28}, Object.class));
    }

    @Test
    void rejectsMissingAndUnknownRecordProperties() {
        assertThrows(JsonException.class, () -> decodePerson("{\"name\":\"Ada\",\"age\":36}"));
        assertThrows(JsonException.class,
                () -> decodePerson("{\"name\":\"Ada\",\"age\":36,\"tags\":[],\"extra\":true}"));
    }

    @Test
    void rejectsOutOfRangeRecordNumbers() {
        assertThrows(JsonException.class,
                () -> decodePerson("{\"name\":\"Ada\",\"age\":2147483648,\"tags\":[]}"));
        assertThrows(JsonException.class,
                () -> decodePerson("{\"name\":\"Ada\",\"age\":1.5,\"tags\":[]}"));
    }

    @Test
    void rejectsCyclesAndUnsupportedPojoTypes() {
        var cycle = new ArrayList<>();
        cycle.add(cycle);

        assertThrows(JsonException.class, () -> codec.encode(cycle));
        JsonException exception = assertThrows(JsonException.class, () -> codec.encode(new Pojo()));
        assertTrue(exception.getMessage().contains("custom JsonCodec"));
    }

    @Test
    void rejectsNonFiniteNumbersAndUnpairedSurrogates() {
        assertThrows(JsonException.class, () -> codec.encode(Double.NaN));
        assertThrows(JsonException.class, () -> codec.encode(new InvalidNumber()));
        assertThrows(JsonException.class, () -> codec.encode("\ud800"));
        assertThrows(JsonException.class, () -> codec.encode("\udc00"));
    }

    @Test
    void encodesPrimitiveArraysWithoutMutatingThem() {
        int[] values = {1, 2, 3};

        assertArrayEquals("[1,2,3]".getBytes(StandardCharsets.UTF_8), codec.encode(values));
        assertArrayEquals(new int[]{1, 2, 3}, values);
    }

    @Test
    void decodesPrimitiveClassTokens() {
        assertEquals(42, codec.decode("42".getBytes(StandardCharsets.UTF_8), int.class));
        assertEquals(true, codec.decode("true".getBytes(StandardCharsets.UTF_8), boolean.class));
        assertThrows(JsonException.class,
                () -> codec.decode("null".getBytes(StandardCharsets.UTF_8), int.class));
    }

    @Test
    void rejectsUnsupportedConcreteGenericTargetsWithJsonErrors() {
        assertThrows(JsonException.class, () -> codec.decode(
                "{\"values\":[\"a\"]}".getBytes(StandardCharsets.UTF_8),
                QueuePayload.class));
        assertThrows(JsonException.class, () -> codec.decode(
                "{\"values\":{\"a\":1}}".getBytes(StandardCharsets.UTF_8),
                SortedMapPayload.class));
    }

    @Test
    void enforcesNestingLimitsForEncodingAndDecoding() {
        String nestedJson = "[".repeat(66) + "0" + "]".repeat(66);
        Object nestedValue = "leaf";
        for (int depth = 0; depth < 66; depth++) {
            nestedValue = List.of(nestedValue);
        }
        Object tooDeep = nestedValue;

        assertThrows(JsonException.class,
                () -> codec.decode(nestedJson.getBytes(StandardCharsets.UTF_8), Object.class));
        assertThrows(JsonException.class, () -> codec.encode(tooDeep));
    }

    private Person decodePerson(String json) {
        return codec.decode(json.getBytes(StandardCharsets.UTF_8), Person.class);
    }

    private record Person(String name, int age, List<String> tags) {
    }

    private record CollectionsPayload(Set<String> roles, Map<String, Integer> scores) {
    }

    private record QueuePayload(Queue<String> values) {
    }

    private record SortedMapPayload(SortedMap<String, Integer> values) {
    }

    private enum State {
        READY
    }

    private static final class Pojo {
    }

    private static final class InvalidNumber extends Number {
        @Override
        public int intValue() {
            return 1;
        }

        @Override
        public long longValue() {
            return 1;
        }

        @Override
        public float floatValue() {
            return 1;
        }

        @Override
        public double doubleValue() {
            return 1;
        }

        @Override
        public String toString() {
            return "+1";
        }
    }
}
