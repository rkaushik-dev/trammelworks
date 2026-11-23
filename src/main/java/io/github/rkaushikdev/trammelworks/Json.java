package io.github.rkaushikdev.trammelworks;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * JSON in exactly the shape the rest of the estate expects it.
 *
 * <p>Batch ids, signatures and spool frames are computed over JSON bytes, and the collector
 * recomputes them, so the encoder here reproduces the collector's encoding byte for byte: ASCII
 * only output with {@code \\u} escapes of four hex digits, the same default separators, optional sorted keys and
 * indentation, and the shortest round-tripping float form ({@code 1e-05}, {@code 10.0}).
 *
 * <p>Decoding returns plain Java values: {@link LinkedHashMap} for objects (insertion ordered,
 * last duplicate wins), {@link ArrayList} for arrays, {@link Long} or {@link BigInteger} for
 * integers, {@link Double} for anything with a fraction or exponent, {@link String}, {@link
 * Boolean} and {@code null}.
 */
public final class Json {

    private Json() {}

    /** How a value is written out. */
    public record Style(
            boolean sortKeys,
            Integer indent,
            String itemSeparator,
            String keySeparator,
            Function<Object, Object> fallback) {

        /** The library default: unsorted, one line, {@code ", "} and {@code ": "}. */
        public static Style plain() {
            return new Style(false, null, ", ", ": ", null);
        }

        /** Sorted keys on one line with the default separators. */
        public static Style sorted() {
            return new Style(true, null, ", ", ": ", null);
        }

        /** Sorted keys, no spaces at all: the form that is hashed and signed. */
        public static Style compact() {
            return new Style(true, null, ",", ":", null);
        }

        /** Sorted keys, indented by {@code indent} spaces per level. */
        public static Style indented(int indent) {
            return new Style(true, indent, ",", ": ", null);
        }

        /** Sorted keys, indented when {@code indent} is given, the defaults otherwise. */
        public static Style sorted(Integer indent) {
            return indent == null ? sorted() : indented(indent);
        }

        /** The same style with keys sorted or not. */
        public Style withSortKeys(boolean sort) {
            return new Style(sort, indent, itemSeparator, keySeparator, fallback);
        }

        /** The same style with explicit separators. */
        public Style withSeparators(String item, String key) {
            return new Style(sortKeys, indent, item, key, fallback);
        }

        /** The same style, turning anything unencodable into {@code fallback.apply(value)}. */
        public Style withFallback(Function<Object, Object> value) {
            return new Style(sortKeys, indent, itemSeparator, keySeparator, value);
        }
    }

    /** Encode with the library defaults. */
    public static String dumps(Object value) {
        return dumps(value, Style.plain());
    }

    /** Encode with a given style. */
    public static String dumps(Object value, Style style) {
        StringBuilder out = new StringBuilder();
        encode(value, style, 0, out);
        return out.toString();
    }

    /** Decode one JSON document. */
    public static Object loads(String text) {
        Parser parser = new Parser(text);
        parser.skipSpace();
        Object value = parser.value();
        parser.skipSpace();
        if (parser.position != text.length()) {
            throw new JsonException("Extra data", parser.position);
        }
        return value;
    }

    /** Decode a document that must be an object. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> loadsObject(String text) {
        Object value = loads(text);
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new JsonException("not a JSON object", 0);
    }

    private static void encode(Object value, Style style, int level, StringBuilder out) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String text) {
            quote(text, out);
        } else if (value instanceof Boolean flag) {
            out.append(flag ? "true" : "false");
        } else if (value instanceof Integer
                || value instanceof Long
                || value instanceof Short
                || value instanceof Byte
                || value instanceof BigInteger) {
            out.append(value);
        } else if (value instanceof Double || value instanceof Float) {
            out.append(floatText(((Number) value).doubleValue()));
        } else if (value instanceof Map<?, ?> map) {
            encodeMap(map, style, level, out);
        } else if (value instanceof List<?> list) {
            encodeList(list, style, level, out);
        } else if (value instanceof Object[] array) {
            encodeList(List.of(array), style, level, out);
        } else if (value instanceof Valued valued) {
            quote(valued.value(), out);
        } else if (style.fallback() != null) {
            encode(style.fallback().apply(value), style, level, out);
        } else {
            throw new JsonException(
                    "Object of type " + value.getClass().getSimpleName() + " is not JSON serializable", -1);
        }
    }

    private static void encodeList(Collection<?> list, Style style, int level, StringBuilder out) {
        if (list.isEmpty()) {
            out.append("[]");
            return;
        }
        out.append('[');
        int inner = level + 1;
        boolean first = true;
        for (Object item : list) {
            if (!first) {
                out.append(style.itemSeparator());
            }
            first = false;
            newline(style, inner, out);
            encode(item, style, inner, out);
        }
        newline(style, level, out);
        out.append(']');
    }

    private static void encodeMap(Map<?, ?> map, Style style, int level, StringBuilder out) {
        List<Map.Entry<String, Object>> entries = new ArrayList<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            entries.add(Map.entry(keyText(entry.getKey()), nullSafe(entry.getValue())));
        }
        if (entries.isEmpty()) {
            out.append("{}");
            return;
        }
        if (style.sortKeys()) {
            entries.sort((left, right) -> compareCodePoints(left.getKey(), right.getKey()));
        }
        out.append('{');
        int inner = level + 1;
        boolean first = true;
        for (Map.Entry<String, Object> entry : entries) {
            if (!first) {
                out.append(style.itemSeparator());
            }
            first = false;
            newline(style, inner, out);
            quote(entry.getKey(), out);
            out.append(style.keySeparator());
            encode(unwrapNull(entry.getValue()), style, inner, out);
        }
        newline(style, level, out);
        out.append('}');
    }

    private static final Object NULL = new Object();

    private static Object nullSafe(Object value) {
        return value == null ? NULL : value;
    }

    private static Object unwrapNull(Object value) {
        return value == NULL ? null : value;
    }

    private static String keyText(Object key) {
        if (key instanceof String text) {
            return text;
        }
        if (key == null) {
            return "null";
        }
        if (key instanceof Boolean flag) {
            return flag ? "true" : "false";
        }
        if (key instanceof Double || key instanceof Float) {
            return floatText(((Number) key).doubleValue());
        }
        if (key instanceof Number || key instanceof Valued) {
            return key instanceof Valued valued ? valued.value() : key.toString();
        }
        throw new JsonException("keys must be str, int, float, bool or None", -1);
    }

    private static void newline(Style style, int level, StringBuilder out) {
        if (style.indent() != null) {
            out.append('\n').append(" ".repeat(style.indent() * level));
        }
    }

    /** Compare by code point, which is how sorted keys are ordered. */
    public static int compareCodePoints(String left, String right) {
        int i = 0;
        int j = 0;
        while (i < left.length() && j < right.length()) {
            int a = left.codePointAt(i);
            int b = right.codePointAt(j);
            if (a != b) {
                return Integer.compare(a, b);
            }
            i += Character.charCount(a);
            j += Character.charCount(b);
        }
        return Integer.compare(left.length() - i, right.length() - j);
    }

    /** A float the way the collector writes it, including the three non-finite spellings. */
    public static String floatText(double value) {
        if (Double.isNaN(value)) {
            return "NaN";
        }
        if (Double.isInfinite(value)) {
            return value > 0 ? "Infinity" : "-Infinity";
        }
        return Repr.of(value);
    }

    /** Append a quoted, ASCII-only string literal. */
    public static void quote(String text, StringBuilder out) {
        out.append('"');
        for (int index = 0; index < text.length(); index++) {
            char c = text.charAt(index);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20 || c > 0x7e) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    /** A string as a quoted JSON literal. */
    public static String quote(String text) {
        StringBuilder out = new StringBuilder();
        quote(text, out);
        return out.toString();
    }

    /** An enum whose JSON form is its wire value rather than its name. */
    public interface Valued {
        /** The string written for this member. */
        String value();

        /**
         * The member of {@code type} whose value is exactly {@code raw}. Exactly: a label value or
         * a config word is read back the way it was written, and the callers that forgive case or
         * padding clean the text first and say so.
         */
        static <E extends Enum<E> & Valued> java.util.Optional<E> lookup(Class<E> type, String raw) {
            for (E member : type.getEnumConstants()) {
                if (member.value().equals(raw)) {
                    return java.util.Optional.of(member);
                }
            }
            return java.util.Optional.empty();
        }
    }

    /** Raised when text does not decode, or a value cannot be encoded. */
    public static final class JsonException extends IllegalArgumentException {
        private final int position;

        JsonException(String message, int position) {
            super(position >= 0 ? message + ": char " + position : message);
            this.position = position;
        }

        /** Where in the text decoding stopped, or -1 for an encoding failure. */
        public int position() {
            return position;
        }
    }

    private static final class Parser {
        private final String text;
        private int position;

        Parser(String text) {
            this.text = text;
        }

        void skipSpace() {
            while (position < text.length()) {
                char c = text.charAt(position);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    position++;
                } else {
                    return;
                }
            }
        }

        Object value() {
            if (position >= text.length()) {
                throw new JsonException("Expecting value", position);
            }
            char c = text.charAt(position);
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 'n' -> literal("null", null);
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'N' -> literal("NaN", Double.NaN);
                case 'I' -> literal("Infinity", Double.POSITIVE_INFINITY);
                default -> {
                    if (c == '-' && text.startsWith("-Infinity", position)) {
                        position += "-Infinity".length();
                        yield Double.NEGATIVE_INFINITY;
                    }
                    yield number();
                }
            };
        }

        private Object literal(String word, Object result) {
            if (!text.startsWith(word, position)) {
                throw new JsonException("Expecting value", position);
            }
            position += word.length();
            return result;
        }

        private Object number() {
            int start = position;
            if (peek() == '-') {
                position++;
            }
            if (peek() == '0') {
                position++;
            } else if (peek() >= '1' && peek() <= '9') {
                while (Character.isDigit(peek()) && peek() < 128) {
                    position++;
                }
            } else {
                position = start;
                throw new JsonException("Expecting value", position);
            }
            boolean integral = true;
            if (peek() == '.' && isDigit(peekAt(position + 1))) {
                integral = false;
                position++;
                while (isDigit(peek())) {
                    position++;
                }
            }
            if (peek() == 'e' || peek() == 'E') {
                int mark = position;
                position++;
                if (peek() == '+' || peek() == '-') {
                    position++;
                }
                if (isDigit(peek())) {
                    integral = false;
                    while (isDigit(peek())) {
                        position++;
                    }
                } else {
                    position = mark;
                }
            }
            String raw = text.substring(start, position);
            if (!integral) {
                return Double.parseDouble(raw);
            }
            BigInteger big = new BigInteger(raw);
            return big.bitLength() < 64 ? (Object) big.longValue() : big;
        }

        private static boolean isDigit(char c) {
            return c >= '0' && c <= '9';
        }

        private char peek() {
            return peekAt(position);
        }

        private char peekAt(int index) {
            return index < text.length() ? text.charAt(index) : '\0';
        }

        private String string() {
            int begin = position;
            position++;
            StringBuilder out = new StringBuilder();
            while (true) {
                if (position >= text.length()) {
                    throw new JsonException("Unterminated string starting at", begin);
                }
                char c = text.charAt(position++);
                if (c == '"') {
                    return out.toString();
                }
                if (c == '\\') {
                    escape(out);
                } else if (c < 0x20) {
                    throw new JsonException("Invalid control character at", position - 1);
                } else {
                    out.append(c);
                }
            }
        }

        private void escape(StringBuilder out) {
            if (position >= text.length()) {
                throw new JsonException("Unterminated string", position);
            }
            char c = text.charAt(position++);
            switch (c) {
                case '"' -> out.append('"');
                case '\\' -> out.append('\\');
                case '/' -> out.append('/');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> out.append(unicode());
                default -> throw new JsonException("Invalid \\escape", position - 2);
            }
        }

        private char unicode() {
            if (position + 4 > text.length()) {
                throw new JsonException("Invalid \\u escape, four hex digits are needed", position - 1);
            }
            String hex = text.substring(position, position + 4);
            for (char h : hex.toCharArray()) {
                if (Character.digit(h, 16) < 0) {
                    throw new JsonException("Invalid \\u escape, four hex digits are needed", position - 1);
                }
            }
            position += 4;
            return (char) Integer.parseInt(hex, 16);
        }

        private Map<String, Object> object() {
            position++;
            Map<String, Object> out = new LinkedHashMap<>();
            skipSpace();
            if (peek() == '}') {
                position++;
                return out;
            }
            while (true) {
                skipSpace();
                if (peek() != '"') {
                    throw new JsonException("Expecting property name enclosed in double quotes", position);
                }
                String key = string();
                skipSpace();
                if (peek() != ':') {
                    throw new JsonException("Expecting ':' delimiter", position);
                }
                position++;
                skipSpace();
                out.put(key, value());
                skipSpace();
                char next = peek();
                position++;
                if (next == '}') {
                    return out;
                }
                if (next != ',') {
                    throw new JsonException("Expecting ',' delimiter", position - 1);
                }
            }
        }

        private List<Object> array() {
            position++;
            List<Object> out = new ArrayList<>();
            skipSpace();
            if (peek() == ']') {
                position++;
                return out;
            }
            while (true) {
                skipSpace();
                out.add(value());
                skipSpace();
                char next = peek();
                position++;
                if (next == ']') {
                    return out;
                }
                if (next != ',') {
                    throw new JsonException("Expecting ',' delimiter", position - 1);
                }
            }
        }
    }
}
