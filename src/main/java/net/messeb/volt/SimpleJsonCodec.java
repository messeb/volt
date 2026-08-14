package net.messeb.volt;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

public final class SimpleJsonCodec implements JsonCodec {
    static final SimpleJsonCodec INSTANCE = new SimpleJsonCodec();
    private static final int MAX_DEPTH = 64;
    private static final Pattern JSON_NUMBER =
            Pattern.compile("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?");

    @Override
    public byte[] encode(Object value) {
        var output = new StringBuilder();
        write(value, output, new IdentityHashMap<>(), 0);
        return output.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public <T> T decode(byte[] json, Class<T> type) {
        if (json == null) {
            throw new IllegalArgumentException("json must not be null");
        }
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        Object parsed = new Parser(decodeUtf8(json)).parse();
        @SuppressWarnings("unchecked")
        T converted = (T) convert(parsed, type);
        return converted;
    }

    private static String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new JsonException("JSON is not valid UTF-8", exception);
        }
    }

    private static void write(
            Object value,
            StringBuilder output,
            IdentityHashMap<Object, Boolean> active,
            int depth) {
        if (depth > MAX_DEPTH) {
            throw new JsonException("JSON nesting exceeds " + MAX_DEPTH);
        }
        if (value == null) {
            output.append("null");
        } else if (value instanceof String string) {
            writeString(string, output);
        } else if (value instanceof Character character) {
            writeString(character.toString(), output);
        } else if (value instanceof Boolean) {
            output.append(value);
        } else if (value instanceof Number number) {
            writeNumber(number, output);
        } else if (value instanceof Enum<?> enumValue) {
            writeString(enumValue.name(), output);
        } else if (value instanceof Optional<?> optional) {
            write(optional.orElse(null), output, active, depth);
        } else if (value instanceof Map<?, ?> map) {
            enter(value, active);
            try {
                output.append('{');
                boolean first = true;
                for (var entry : map.entrySet()) {
                    if (!(entry.getKey() instanceof String key)) {
                        throw new JsonException("JSON object keys must be strings");
                    }
                    if (!first) {
                        output.append(',');
                    }
                    first = false;
                    writeString(key, output);
                    output.append(':');
                    write(entry.getValue(), output, active, depth + 1);
                }
                output.append('}');
            } finally {
                active.remove(value);
            }
        } else if (value instanceof Iterable<?> iterable) {
            enter(value, active);
            try {
                output.append('[');
                boolean first = true;
                for (Object element : iterable) {
                    if (!first) {
                        output.append(',');
                    }
                    first = false;
                    write(element, output, active, depth + 1);
                }
                output.append(']');
            } finally {
                active.remove(value);
            }
        } else if (value.getClass().isArray()) {
            enter(value, active);
            try {
                output.append('[');
                for (int index = 0; index < Array.getLength(value); index++) {
                    if (index > 0) {
                        output.append(',');
                    }
                    write(Array.get(value, index), output, active, depth + 1);
                }
                output.append(']');
            } finally {
                active.remove(value);
            }
        } else if (value.getClass().isRecord()) {
            writeRecord(value, output, active, depth);
        } else {
            throw new JsonException(
                    "built-in JSON supports maps, collections, arrays, and records; "
                            + "configure a custom JsonCodec for " + value.getClass().getName());
        }
    }

    private static void writeRecord(
            Object record,
            StringBuilder output,
            IdentityHashMap<Object, Boolean> active,
            int depth) {
        enter(record, active);
        try {
            output.append('{');
            RecordComponent[] components = record.getClass().getRecordComponents();
            for (int index = 0; index < components.length; index++) {
                if (index > 0) {
                    output.append(',');
                }
                RecordComponent component = components[index];
                writeString(component.getName(), output);
                output.append(':');
                try {
                    var accessor = component.getAccessor();
                    if (!accessor.canAccess(record) && !accessor.trySetAccessible()) {
                        throw new JsonException("cannot access record component: " + component.getName());
                    }
                    write(accessor.invoke(record), output, active, depth + 1);
                } catch (IllegalAccessException | InvocationTargetException exception) {
                    throw new JsonException(
                            "cannot read record component: " + component.getName(), exception);
                }
            }
            output.append('}');
        } finally {
            active.remove(record);
        }
    }

    private static void writeNumber(Number number, StringBuilder output) {
        if (number instanceof Double doubleValue && !Double.isFinite(doubleValue)
                || number instanceof Float floatValue && !Float.isFinite(floatValue)) {
            throw new JsonException("JSON cannot represent non-finite numbers");
        }
        String encoded = number.toString();
        if (!JSON_NUMBER.matcher(encoded).matches()) {
            throw new JsonException("number does not have a valid JSON representation");
        }
        output.append(encoded);
    }

    private static void enter(Object value, IdentityHashMap<Object, Boolean> active) {
        if (active.put(value, Boolean.TRUE) != null) {
            throw new JsonException("cyclic value cannot be encoded as JSON");
        }
    }

    private static void writeString(String value, StringBuilder output) {
        output.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> output.append("\\\"");
                case '\\' -> output.append("\\\\");
                case '\b' -> output.append("\\b");
                case '\f' -> output.append("\\f");
                case '\n' -> output.append("\\n");
                case '\r' -> output.append("\\r");
                case '\t' -> output.append("\\t");
                default -> {
                    if (character < 0x20 || character == '\u2028' || character == '\u2029') {
                        output.append("\\u").append(String.format("%04x", (int) character));
                    } else if (Character.isHighSurrogate(character)) {
                        if (index + 1 >= value.length()
                                || !Character.isLowSurrogate(value.charAt(index + 1))) {
                            throw new JsonException("string contains an unpaired surrogate");
                        }
                        output.append(character).append(value.charAt(++index));
                    } else if (Character.isLowSurrogate(character)) {
                        throw new JsonException("string contains an unpaired surrogate");
                    } else {
                        output.append(character);
                    }
                }
            }
        }
        output.append('"');
    }

    private static Object convert(Object value, Type target) {
        if (target instanceof Class<?> type) {
            return convertClass(value, type);
        }
        if (target instanceof ParameterizedType parameterized) {
            return convertParameterized(value, parameterized);
        }
        throw new JsonException("unsupported JSON target type: " + target.getTypeName());
    }

    private static Object convertClass(Object value, Class<?> type) {
        if (value == null) {
            if (type.isPrimitive()) {
                throw new JsonException("null cannot be assigned to " + type.getName());
            }
            return null;
        }
        if (type == Object.class || type.isInstance(value)) {
            return value;
        }
        if (type == String.class) {
            if (value instanceof String) {
                return value;
            }
            throw expected(type, value);
        }
        if (type == boolean.class || type == Boolean.class) {
            if (value instanceof Boolean) {
                return value;
            }
            throw expected(type, value);
        }
        if (type == char.class || type == Character.class) {
            if (value instanceof String string && string.length() == 1) {
                return string.charAt(0);
            }
            throw expected(type, value);
        }
        if (type.isEnum()) {
            if (!(value instanceof String name)) {
                throw expected(type, value);
            }
            try {
                @SuppressWarnings({"unchecked", "rawtypes"})
                Object result = Enum.valueOf((Class<? extends Enum>) type, name);
                return result;
            } catch (IllegalArgumentException exception) {
                throw new JsonException("unknown " + type.getSimpleName() + " value: " + name, exception);
            }
        }
        if (isNumeric(type)) {
            if (!(value instanceof BigDecimal number)) {
                throw expected(type, value);
            }
            return convertNumber(number, type);
        }
        if (type.isArray()) {
            if (!(value instanceof List<?> elements)) {
                throw expected(type, value);
            }
            Object result = Array.newInstance(type.componentType(), elements.size());
            for (int index = 0; index < elements.size(); index++) {
                Array.set(result, index, convert(elements.get(index), type.componentType()));
            }
            return result;
        }
        if (type.isRecord()) {
            return convertRecord(value, type);
        }
        throw new JsonException(
                "built-in JSON decoding supports scalars, arrays, collections, maps, and records; "
                        + "configure a custom JsonCodec for " + type.getName());
    }

    private static Object convertParameterized(Object value, ParameterizedType target) {
        if (!(target.getRawType() instanceof Class<?> rawType)) {
            throw new JsonException("unsupported JSON target type: " + target.getTypeName());
        }
        Type[] arguments = target.getActualTypeArguments();
        if (rawType == Optional.class) {
            return Optional.ofNullable(convert(value, arguments[0]));
        }
        if (Collection.class.isAssignableFrom(rawType)) {
            if (!(value instanceof List<?> elements)) {
                throw expected(rawType, value);
            }
            Collection<Object> result = Set.class.isAssignableFrom(rawType)
                    ? new LinkedHashSet<>()
                    : new ArrayList<>();
            if (!rawType.isInstance(result)) {
                throw new JsonException(
                        "unsupported JSON collection target: " + rawType.getTypeName());
            }
            for (Object element : elements) {
                result.add(convert(element, arguments[0]));
            }
            return result;
        }
        if (Map.class.isAssignableFrom(rawType)) {
            if (!(value instanceof Map<?, ?> entries)) {
                throw expected(rawType, value);
            }
            if (arguments[0] != String.class) {
                throw new JsonException("JSON object target keys must be strings");
            }
            var result = new LinkedHashMap<String, Object>();
            if (!rawType.isInstance(result)) {
                throw new JsonException("unsupported JSON map target: " + rawType.getTypeName());
            }
            for (var entry : entries.entrySet()) {
                result.put((String) entry.getKey(), convert(entry.getValue(), arguments[1]));
            }
            return result;
        }
        return convertClass(value, rawType);
    }

    private static Object convertRecord(Object value, Class<?> type) {
        if (!(value instanceof Map<?, ?> object)) {
            throw expected(type, value);
        }
        RecordComponent[] components = type.getRecordComponents();
        var expectedNames = new LinkedHashSet<String>();
        var constructorTypes = new Class<?>[components.length];
        var arguments = new Object[components.length];
        for (int index = 0; index < components.length; index++) {
            RecordComponent component = components[index];
            expectedNames.add(component.getName());
            constructorTypes[index] = component.getType();
            if (!object.containsKey(component.getName())) {
                throw new JsonException("missing JSON property: " + component.getName());
            }
            arguments[index] = convert(object.get(component.getName()), component.getGenericType());
        }
        for (Object key : object.keySet()) {
            if (!expectedNames.contains(key)) {
                throw new JsonException("unknown JSON property for " + type.getSimpleName() + ": " + key);
            }
        }
        try {
            Constructor<?> constructor = type.getDeclaredConstructor(constructorTypes);
            if (!constructor.canAccess(null) && !constructor.trySetAccessible()) {
                throw new JsonException("cannot access record constructor: " + type.getName());
            }
            return constructor.newInstance(arguments);
        } catch (NoSuchMethodException | InstantiationException
                | IllegalAccessException | IllegalArgumentException exception) {
            throw new JsonException("cannot construct record: " + type.getName(), exception);
        } catch (InvocationTargetException exception) {
            throw new JsonException(
                    "record constructor rejected JSON: " + type.getName(), exception.getCause());
        }
    }

    private static boolean isNumeric(Class<?> type) {
        return type == byte.class || type == Byte.class
                || type == short.class || type == Short.class
                || type == int.class || type == Integer.class
                || type == long.class || type == Long.class
                || type == float.class || type == Float.class
                || type == double.class || type == Double.class
                || type == BigInteger.class || type == BigDecimal.class;
    }

    private static Object convertNumber(BigDecimal number, Class<?> type) {
        try {
            if (type == byte.class || type == Byte.class) {
                return number.byteValueExact();
            }
            if (type == short.class || type == Short.class) {
                return number.shortValueExact();
            }
            if (type == int.class || type == Integer.class) {
                return number.intValueExact();
            }
            if (type == long.class || type == Long.class) {
                return number.longValueExact();
            }
            if (type == BigInteger.class) {
                return number.toBigIntegerExact();
            }
            if (type == BigDecimal.class) {
                return number;
            }
            if (type == float.class || type == Float.class) {
                float result = number.floatValue();
                if (!Float.isFinite(result)) {
                    throw new ArithmeticException("float overflow");
                }
                return result;
            }
            double result = number.doubleValue();
            if (!Double.isFinite(result)) {
                throw new ArithmeticException("double overflow");
            }
            return result;
        } catch (ArithmeticException exception) {
            throw new JsonException("number is out of range for " + type.getSimpleName(), exception);
        }
    }

    private static JsonException expected(Class<?> type, Object value) {
        return new JsonException(
                "expected " + type.getSimpleName() + " but found " + value.getClass().getSimpleName());
    }

    private static final class Parser {
        private final String input;
        private int index;

        private Parser(String input) {
            this.input = input;
        }

        private Object parse() {
            skipWhitespace();
            Object value = parseValue(0);
            skipWhitespace();
            if (index != input.length()) {
                throw error("unexpected trailing content");
            }
            return value;
        }

        private Object parseValue(int depth) {
            if (depth > MAX_DEPTH) {
                throw error("JSON nesting exceeds " + MAX_DEPTH);
            }
            if (index >= input.length()) {
                throw error("unexpected end of JSON");
            }
            return switch (input.charAt(index)) {
                case '"' -> parseString();
                case '{' -> parseObject(depth);
                case '[' -> parseArray(depth);
                case 't' -> parseLiteral("true", Boolean.TRUE);
                case 'f' -> parseLiteral("false", Boolean.FALSE);
                case 'n' -> parseLiteral("null", null);
                default -> parseNumber();
            };
        }

        private Map<String, Object> parseObject(int depth) {
            index++;
            var result = new LinkedHashMap<String, Object>();
            skipWhitespace();
            if (consume('}')) {
                return result;
            }
            while (true) {
                if (index >= input.length() || input.charAt(index) != '"') {
                    throw error("object key must be a string");
                }
                String key = parseString();
                skipWhitespace();
                require(':');
                skipWhitespace();
                Object value = parseValue(depth + 1);
                if (result.containsKey(key)) {
                    throw error("duplicate object key: " + key);
                }
                result.put(key, value);
                skipWhitespace();
                if (consume('}')) {
                    return result;
                }
                require(',');
                skipWhitespace();
            }
        }

        private List<Object> parseArray(int depth) {
            index++;
            var result = new ArrayList<>();
            skipWhitespace();
            if (consume(']')) {
                return result;
            }
            while (true) {
                result.add(parseValue(depth + 1));
                skipWhitespace();
                if (consume(']')) {
                    return result;
                }
                require(',');
                skipWhitespace();
            }
        }

        private String parseString() {
            index++;
            var result = new StringBuilder();
            while (index < input.length()) {
                char character = input.charAt(index++);
                if (character == '"') {
                    validateSurrogates(result);
                    return result.toString();
                }
                if (character == '\\') {
                    if (index >= input.length()) {
                        throw error("unterminated escape sequence");
                    }
                    char escape = input.charAt(index++);
                    switch (escape) {
                        case '"' -> result.append('"');
                        case '\\' -> result.append('\\');
                        case '/' -> result.append('/');
                        case 'b' -> result.append('\b');
                        case 'f' -> result.append('\f');
                        case 'n' -> result.append('\n');
                        case 'r' -> result.append('\r');
                        case 't' -> result.append('\t');
                        case 'u' -> result.append(parseUnicodeEscape());
                        default -> throw error("invalid escape sequence");
                    }
                } else {
                    if (character < 0x20) {
                        throw error("unescaped control character in string");
                    }
                    result.append(character);
                }
            }
            throw error("unterminated string");
        }

        private char parseUnicodeEscape() {
            if (index + 4 > input.length()) {
                throw error("incomplete unicode escape");
            }
            int value = 0;
            for (int offset = 0; offset < 4; offset++) {
                int digit = Character.digit(input.charAt(index++), 16);
                if (digit < 0) {
                    throw error("invalid unicode escape");
                }
                value = value * 16 + digit;
            }
            return (char) value;
        }

        private BigDecimal parseNumber() {
            int start = index;
            consume('-');
            if (consume('0')) {
                if (index < input.length() && Character.isDigit(input.charAt(index))) {
                    throw error("leading zero in number");
                }
            } else {
                requireDigit();
                while (index < input.length() && Character.isDigit(input.charAt(index))) {
                    index++;
                }
            }
            if (consume('.')) {
                requireDigit();
                while (index < input.length() && Character.isDigit(input.charAt(index))) {
                    index++;
                }
            }
            if (index < input.length() && (input.charAt(index) == 'e' || input.charAt(index) == 'E')) {
                index++;
                if (index < input.length() && (input.charAt(index) == '+' || input.charAt(index) == '-')) {
                    index++;
                }
                requireDigit();
                while (index < input.length() && Character.isDigit(input.charAt(index))) {
                    index++;
                }
            }
            if (index == start) {
                throw error("expected JSON value");
            }
            try {
                return new BigDecimal(input.substring(start, index));
            } catch (NumberFormatException exception) {
                throw error("invalid number");
            }
        }

        private void requireDigit() {
            if (index >= input.length() || !Character.isDigit(input.charAt(index))) {
                throw error("expected digit");
            }
        }

        private Object parseLiteral(String literal, Object value) {
            if (!input.startsWith(literal, index)) {
                throw error("invalid JSON value");
            }
            index += literal.length();
            return value;
        }

        private void validateSurrogates(StringBuilder value) {
            for (int offset = 0; offset < value.length(); offset++) {
                char character = value.charAt(offset);
                if (Character.isHighSurrogate(character)) {
                    if (offset + 1 >= value.length()
                            || !Character.isLowSurrogate(value.charAt(offset + 1))) {
                        throw error("unpaired surrogate in string");
                    }
                    offset++;
                } else if (Character.isLowSurrogate(character)) {
                    throw error("unpaired surrogate in string");
                }
            }
        }

        private void skipWhitespace() {
            while (index < input.length()) {
                char character = input.charAt(index);
                if (character != ' ' && character != '\t'
                        && character != '\r' && character != '\n') {
                    return;
                }
                index++;
            }
        }

        private boolean consume(char expected) {
            if (index < input.length() && input.charAt(index) == expected) {
                index++;
                return true;
            }
            return false;
        }

        private void require(char expected) {
            if (!consume(expected)) {
                throw error("expected '" + expected + "'");
            }
        }

        private JsonException error(String message) {
            return new JsonException(message + " at character " + index);
        }
    }
}
