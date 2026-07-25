package dev.cauce.governance.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CanonicalJsonTest {

    @Test
    void serialize_mapsWithDifferentKeyInsertionOrder_produceIdenticalOutput() {
        Map<String, Object> ab = new LinkedHashMap<>();
        ab.put("a", 1);
        ab.put("b", 2);
        Map<String, Object> ba = new LinkedHashMap<>();
        ba.put("b", 2);
        ba.put("a", 1);

        assertThat(CanonicalJson.serialize(ab))
                .isEqualTo(CanonicalJson.serialize(ba))
                .isEqualTo("{\"a\":1,\"b\":2}");
    }

    @Test
    void serialize_nestedStructures_sortKeysAtEveryLevelAndKeepListOrder() {
        Map<String, Object> value = Map.of(
                "outer", Map.of("z", true, "a", List.of(3, 1, 2)),
                "empty", Map.of());

        assertThat(CanonicalJson.serialize(value))
                .isEqualTo("{\"empty\":{},\"outer\":{\"a\":[3,1,2],\"z\":true}}");
    }

    @Test
    void serialize_equalNumericValuesOfDifferentJavaTypes_collapseToTheSameForm() {
        // A jsonb round trip may hand back a different Number type for the same value; the
        // canonical form must not depend on it.
        assertThat(CanonicalJson.serialize(Map.of("n", 1)))
                .isEqualTo(CanonicalJson.serialize(Map.of("n", 1L)))
                .isEqualTo(CanonicalJson.serialize(Map.of("n", 1.0)))
                .isEqualTo("{\"n\":1}");
        assertThat(CanonicalJson.serialize(Map.of("n", 2.50)))
                .isEqualTo("{\"n\":2.5}");
    }

    @Test
    void serialize_stringsWithSpecialCharacters_escapesThemDeterministically() {
        String input = "a\"b\\c" + '\n' + "d" + '\t' + "e" + (char) 0x01 + "f";

        String canonical = CanonicalJson.serialize(input);

        assertThat(canonical)
                .isEqualTo("\"a\\\"b\\\\c" + "\\n" + "d" + "\\t" + "e" + "\\u0001" + "f\"");
    }

    @Test
    void serialize_nullAndBooleans_useJsonLiterals() {
        Map<String, Object> withNull = new LinkedHashMap<>();
        withNull.put("x", null);
        withNull.put("y", false);

        assertThat(CanonicalJson.serialize(withNull)).isEqualTo("{\"x\":null,\"y\":false}");
    }

    @Test
    void serialize_nonFiniteNumber_throwsIllegalArgument() {
        assertThatThrownBy(() -> CanonicalJson.serialize(Map.of("n", Double.NaN)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-finite");
    }

    @Test
    void serialize_nonStringMapKey_throwsIllegalArgument() {
        assertThatThrownBy(() -> CanonicalJson.serialize(Map.of(1, "v")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("keys must be strings");
    }
}
