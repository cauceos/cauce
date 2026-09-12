package dev.cauce.governance.audit;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Deterministic, canonical JSON serialization of the plain values a jsonb payload maps to
 * (Map, List, String, Number, Boolean, null). This is what the audit hash scheme (v1) hashes
 * over — NEVER the database's own jsonb text representation, whose key order, spacing, and
 * number formatting are not contractual and would break verification.
 *
 * <p>Canonical form: object keys sorted by UTF-16 CODE UNIT (what {@link TreeMap} over
 * {@link String} gives, and what RFC 8785 specifies — NOT code point order, which differs
 * for characters outside the Basic Multilingual Plane), no whitespace, strings with the
 * minimal JSON escapes, and numbers normalized through
 * {@code new BigDecimal(n.toString()).stripTrailingZeros().toPlainString()} so equal values
 * of different Java number types ({@code 1}, {@code 1L}, {@code 1.0}) serialize identically.
 * NaN and infinities are rejected — they are not representable in JSON and could never round
 * trip through jsonb.
 *
 * <p>The normative description of this format, with test vectors, is
 * {@code docs/spec/audit-chain-format.md}. This class implements it.
 *
 * <p>{@code normalizeUnicode} selects the audit scheme's string handling and is passed down
 * to every key and string value: v2 normalizes to Unicode NFC before escaping, v1 does not.
 * It is a parameter rather than a constant because the two must coexist — normalizing
 * unconditionally would change the hash of already-written v1 entries whose text is not in
 * NFC, which is a history rewrite (see {@link AuditChainHasher}).
 */
final class CanonicalJson {

    private CanonicalJson() {
    }

    /** Serializes {@code value} to its canonical JSON form, without Unicode normalization. */
    static String serialize(Object value) {
        return serialize(value, false);
    }

    /** Serializes {@code value} to its canonical JSON form, NFC-normalizing strings if asked. */
    static String serialize(Object value, boolean normalizeUnicode) {
        StringBuilder out = new StringBuilder();
        write(value, out, normalizeUnicode);
        return out.toString();
    }

    private static void write(Object value, StringBuilder out, boolean normalizeUnicode) {
        switch (value) {
            case null -> out.append("null");
            case Map<?, ?> map -> writeObject(map, out, normalizeUnicode);
            case List<?> list -> writeArray(list, out, normalizeUnicode);
            case String s -> writeString(s, out, normalizeUnicode);
            case Boolean b -> out.append(b.booleanValue());
            case Number n -> writeNumber(n, out);
            default -> throw new IllegalArgumentException(
                    "value type is not canonicalizable: " + value.getClass().getName());
        }
    }

    private static void writeObject(Map<?, ?> map, StringBuilder out, boolean normalizeUnicode) {
        TreeMap<String, Object> sorted = new TreeMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException("object keys must be strings, got: "
                        + (entry.getKey() == null ? "null" : entry.getKey().getClass().getName()));
            }
            // Normalized BEFORE sorting: under v2 the key order must be the order of the
            // forms actually written, or two keys equal in NFC could sort by their raw bytes.
            sorted.put(normalizeUnicode ? Normalizer.normalize(key, Normalizer.Form.NFC) : key,
                    entry.getValue());
        }
        out.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> entry : sorted.entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            writeString(entry.getKey(), out, normalizeUnicode);
            out.append(':');
            write(entry.getValue(), out, normalizeUnicode);
        }
        out.append('}');
    }

    private static void writeArray(List<?> list, StringBuilder out, boolean normalizeUnicode) {
        out.append('[');
        boolean first = true;
        for (Object element : list) {
            if (!first) {
                out.append(',');
            }
            first = false;
            write(element, out, normalizeUnicode);
        }
        out.append(']');
    }

    private static void writeString(String raw, StringBuilder out, boolean normalizeUnicode) {
        String s = normalizeUnicode ? Normalizer.normalize(raw, Normalizer.Form.NFC) : raw;
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
