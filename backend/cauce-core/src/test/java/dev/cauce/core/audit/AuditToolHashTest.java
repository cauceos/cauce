package dev.cauce.core.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuditToolHashTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void of_matchesThePublishedVectors() {
        // Computed outside Java: sha256("cauce-tool:v1:" + tenant + ":" + canonical_json(value)).
        assertThat(AuditToolHash.of(TENANT, Map.of("tz", "UTC")))
                .isEqualTo("c6bc66c84ec00d8fe688137dc1d49994a08ab77bdd59e25ebc3219197cc1c2d4");
        assertThat(AuditToolHash.of(TENANT, "10:15"))
                .isEqualTo("47e996141ce415480a5dc139f04a40a07cdbc1f8bc34debfca1768578043d056");
        assertThat(AuditToolHash.of(TENANT, Map.of()))
                .isEqualTo("793a69aa841b41563205c132eb5540b0829a87f830fc201ce4219167645086dd");
    }

    @Test
    void of_isIndependentOfKeyOrder() {
        Map<String, Object> ab = new LinkedHashMap<>();
        ab.put("a", 1);
        ab.put("b", "x");
        Map<String, Object> ba = new LinkedHashMap<>();
        ba.put("b", "x");
        ba.put("a", 1);

        assertThat(AuditToolHash.of(TENANT, ab)).isEqualTo(AuditToolHash.of(TENANT, ba));
    }

    @Test
    void of_normalisesToNfc_soEquivalentSpellingsHashAlike() {
        String composed = "café";
        String decomposed = "café";

        assertThat(AuditToolHash.of(TENANT, composed)).isEqualTo(AuditToolHash.of(TENANT, decomposed));
    }

    @Test
    void of_isBoundToTheTenant() {
        assertThat(AuditToolHash.of(TENANT, "10:15")).isNotEqualTo(AuditToolHash.of(UUID.randomUUID(), "10:15"));
    }

    @Test
    void of_differsFromTheContentHash_ofTheSameText() {
        assertThat(AuditToolHash.of(TENANT, "10:15")).isNotEqualTo(AuditContentHash.of(TENANT, "10:15"));
    }

    @Test
    void of_rejectsANullTenant() {
        assertThatThrownBy(() -> AuditToolHash.of(null, "x")).isInstanceOf(NullPointerException.class);
    }
}
