package dev.cauce.governance.audit;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Deterministic, canonical JSON serialization of the plain values a jsonb payload maps to
 * (Map, List, String, Number, Boolean, null). This is what the audit hash scheme (v1) hashes
 * over — NEVER the database's own jsonb text representation, whose key order, spacing, and
 * number formatting are not contractual and would break verification.
 *
 * <p>Canonical form: object keys sorted by code point, no whitespace, strings with the
 * minimal JSON escapes, and numbers normalized through
 * {@code new BigDecimal(n.toString()).stripTrailingZeros().toPlainString()} so equal values
 * of different Java number types ({@code 1}, {@code 1L}, {@code 1.0}) serialize identically.
 * NaN and infinities are rejected — they are not representable in JSON and could never round
 * trip through jsonb.
 */
final class CanonicalJson {

    private CanonicalJson() {
    }

    /** Serializes {@code value} to its canonical JSON form. */
    static String serialize(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out);
        return out.toString();
    }

    private static void write(Object value, StringBuilder out) {
        switch (value) {
            case null -> out.append("null");
            case Map<?, ?> map -> writeObject(map, out);
            case List<?> list -> writeArray(list, out);
            case String s -> writeString(s, out);
            case Boolean b -> out.append(b.booleanValue());
            case Number n -> writeNumber(n, out);
            default -> throw new IllegalArgumentException(
                    "value type is not canonicalizable: " + value.getClass().getName());
        }
    }

    private static void writeObject(Map<?, ?> map, StringBuilder out) {
        TreeMap<String, Object> sorted = new TreeMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException("object keys must be strings, got: "
                        + (entry.getKey() == null ? "null" : entry.getKey().getClass().getName()));
            }
            sorted.put(key, entry.getValue());
        }
        out.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> entry : sorted.entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            writeString(entry.getKey(), out);
            out.append(':');
            write(entry.getValue(), out);
        }
        out.append('}');
    }

    private static void writeArray(List<?> list, StringBuilder out) {
        out.append('[');
        boolean first = true;
        for (Object element : list) {
            if (!first) {
                out.append(',');
            }
            first = false;
            write(element, out);
        }
        out.append(']');
    }

    private static void writeString(String s, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    private static void writeNumber(Number n, StringBuilder out) {
        if (n instanceof Double d && (d.isNaN() || d.isInfinite())
                || n instanceof Float f && (f.isNaN() || f.isInfinite())) {
            throw new IllegalArgumentException("non-finite numbers are not canonicalizable: " + n);
        }
        out.append(new BigDecimal(n.toString()).stripTrailingZeros().toPlainString());
    }
}
