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

final class PathPattern {
    private final String source;
    private final List<Segment> segments;
    private final String shape;
    private final int staticSegments;
    private final boolean wildcard;

    private PathPattern(
            String source,
            List<Segment> segments,
            String shape,
            int staticSegments,
            boolean wildcard) {
        this.source = source;
        this.segments = segments;
        this.shape = shape;
        this.staticSegments = staticSegments;
        this.wildcard = wildcard;
    }

    static PathPattern compile(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("route path must not be blank");
        }
        if (path.charAt(0) != '/') {
            throw new IllegalArgumentException("route path must start with '/'");
        }
        if (path.indexOf('?') >= 0 || path.indexOf('#') >= 0) {
            throw new IllegalArgumentException("route path must not contain a query or fragment");
        }
        if (path.indexOf('\r') >= 0 || path.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("route path must be a single line");
        }
        if (path.equals("/")) {
            return new PathPattern(path, List.of(), "/", 0, false);
        }

        String[] values = path.substring(1).split("/", -1);
        var segments = new ArrayList<Segment>(values.length);
        var names = new java.util.HashSet<String>();
        int staticSegments = 0;
        boolean wildcard = false;
        for (int index = 0; index < values.length; index++) {
            String value = values[index];
            if (value.equals("*")) {
                if (index != values.length - 1) {
                    throw new IllegalArgumentException("wildcard must be the final route segment");
                }
                wildcard = true;
                segments.add(new Segment(SegmentType.WILDCARD, "*"));
            } else if (value.startsWith("{") || value.endsWith("}")) {
                if (value.length() < 3 || value.charAt(0) != '{' || value.charAt(value.length() - 1) != '}') {
                    throw new IllegalArgumentException("invalid path parameter segment: " + value);
                }
                String name = value.substring(1, value.length() - 1);
                if (name.isBlank() || !names.add(name)) {
                    throw new IllegalArgumentException("path parameter names must be non-blank and unique: " + name);
                }
                segments.add(new Segment(SegmentType.PARAMETER, name));
            } else {
                staticSegments++;
                segments.add(new Segment(SegmentType.STATIC, value));
            }
        }
        String shape = "/" + segments.stream()
                .map(segment -> switch (segment.type()) {
                    case STATIC -> segment.value();
                    case PARAMETER -> "{}";
                    case WILDCARD -> "*";
                })
                .collect(java.util.stream.Collectors.joining("/"));
        return new PathPattern(path, List.copyOf(segments), shape, staticSegments, wildcard);
    }

    Match match(RequestPath path) {
        int requiredSegments = wildcard ? segments.size() - 1 : segments.size();
        if ((!wildcard && path.segments().size() != requiredSegments)
                || (wildcard && path.segments().size() < requiredSegments)) {
            return null;
        }

        var parameters = new LinkedHashMap<String, String>();
        for (int index = 0; index < requiredSegments; index++) {
            Segment segment = segments.get(index);
            String actual = path.segments().get(index);
            if (segment.type() == SegmentType.STATIC && !segment.value().equals(actual)) {
                return null;
            }
            if (segment.type() == SegmentType.PARAMETER) {
                parameters.put(segment.value(), actual);
            }
        }
        if (wildcard) {
            parameters.put("*", String.join("/", path.segments().subList(requiredSegments, path.segments().size())));
        }
        return new Match(Map.copyOf(parameters));
    }

    String source() {
        return source;
    }

    String shape() {
        return shape;
    }

    int compareSpecificity(PathPattern other) {
        int length = Math.max(segments.size(), other.segments.size());
        for (int index = 0; index < length; index++) {
            int comparison = Integer.compare(specificityAt(index), other.specificityAt(index));
            if (comparison != 0) {
                return comparison;
            }
        }
        return 0;
    }

    int staticSegments() {
        return staticSegments;
    }

    int segmentCount() {
        return segments.size();
    }

    boolean wildcard() {
        return wildcard;
    }

    private int specificityAt(int index) {
        if (index >= segments.size()) {
            return 1;
        }
        return switch (segments.get(index).type()) {
            case STATIC -> 3;
            case PARAMETER -> 2;
            case WILDCARD -> 0;
        };
    }

    record Match(Map<String, String> parameters) {
    }

    record RequestPath(String value, List<String> segments) {
        static RequestPath parse(String rawPath) {
            String value = rawPath == null || rawPath.isEmpty() ? "/" : rawPath;
            if (value.charAt(0) != '/') {
                throw new BadRequest("request path must start with '/'");
            }
            if (value.equals("/")) {
                return new RequestPath(value, List.of());
            }
            String[] rawSegments = value.substring(1).split("/", -1);
            var decoded = new ArrayList<String>(rawSegments.length);
            for (String segment : rawSegments) {
                decoded.add(decodeSegment(segment));
            }
            return new RequestPath(value, List.copyOf(decoded));
        }

        private static String decodeSegment(String value) {
            var result = new StringBuilder(value.length());
            for (int index = 0; index < value.length();) {
                if (value.charAt(index) != '%') {
                    int codePoint = value.codePointAt(index);
                    result.appendCodePoint(codePoint);
                    index += Character.charCount(codePoint);
                    continue;
                }
                var bytes = new ByteArrayOutputStream();
                while (index < value.length() && value.charAt(index) == '%') {
                    if (index + 2 >= value.length()) {
                        throw new BadRequest("malformed percent encoding in request path");
                    }
                    int high = Character.digit(value.charAt(index + 1), 16);
                    int low = Character.digit(value.charAt(index + 2), 16);
                    if (high < 0 || low < 0) {
                        throw new BadRequest("malformed percent encoding in request path");
                    }
                    bytes.write(high * 16 + low);
                    index += 3;
                }
                try {
                    result.append(StandardCharsets.UTF_8.newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(bytes.toByteArray())));
                } catch (CharacterCodingException exception) {
                    var badRequest = new BadRequest("request path is not valid UTF-8");
                    badRequest.initCause(exception);
                    throw badRequest;
                }
            }
            return result.toString();
        }
    }

    private enum SegmentType {
        STATIC,
        PARAMETER,
        WILDCARD
    }

    private record Segment(SegmentType type, String value) {
    }
}
