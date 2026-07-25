package dev.cauce.core.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuditContentHashTest {

    private final UUID tenantId = UUID.randomUUID();

    @Test
    void of_sameTenantAndContent_isDeterministicHex64() {
        String hash = AuditContentHash.of(tenantId, "Hola");

        assertThat(hash).isEqualTo(AuditContentHash.of(tenantId, "Hola"))
                .hasSize(64)
                .matches("[0-9a-f]{64}");
    }

    @Test
    void of_differentContent_producesDifferentHash() {
        assertThat(AuditContentHash.of(tenantId, "Hola"))
                .isNotEqualTo(AuditContentHash.of(tenantId, "Hola!"));
    }

    @Test
    void of_sameContentDifferentTenant_producesDifferentHash() {
        // Tenant binding: equal content never correlates across tenants.
        assertThat(AuditContentHash.of(tenantId, "Hola"))
                .isNotEqualTo(AuditContentHash.of(UUID.randomUUID(), "Hola"));
    }

    @Test
    void of_isDomainSeparated_notAPlainContentDigest() throws Exception {
        // A plain SHA-256 of the content must not equal the audit content hash (domain
        // prefix + tenant binding), so generic rainbow tables do not apply.
        String plainSha256OfHola = java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256")
                        .digest("Hola".getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        assertThat(AuditContentHash.of(tenantId, "Hola")).isNotEqualTo(plainSha256OfHola);
    }

    @Test
    void of_nullContent_throwsNpe() {
        assertThatThrownBy(() -> AuditContentHash.of(tenantId, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("content");
    }
}
