package dev.cauce.core.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.cauce.core.message.MessagePart;
import dev.cauce.core.tool.ToolCall;
import dev.cauce.core.tool.ToolResult;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuditContentHashTest {

    private final UUID tenantId = UUID.randomUUID();

    /** The tenant of the vectors published in docs/spec/audit-chain-format.md §6.3. */
    private static final UUID SPEC_TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void of_matchesThePublishedV1Vector() {
        assertThat(AuditContentHash.of(SPEC_TENANT, "Hola"))
                .isEqualTo("afe04b403af734d44a3390b5ad13c72eef270f1392a892d12458ad1a89eaf1e8");
    }

    @Test
    void ofParts_matchesThePublishedV2Vectors() {
        assertThat(AuditContentHash.ofParts(SPEC_TENANT, List.of(new MessagePart.Text("Hola"))))
                .isEqualTo("af8bb27c1a8c17c8ae3084111c4b86480f38b1c44c5055cacc7f77f1719acda7");
        assertThat(AuditContentHash.ofParts(SPEC_TENANT, List.of(new MessagePart.ToolCallPart(
                new ToolCall("call-1", "get_current_time", Map.of("tz", "UTC"))))))
                .isEqualTo("e0838bc04dd8e78b5d199d37823bc700e9956ee9fb9affe3e6fed5da028ab2bf");
        assertThat(AuditContentHash.ofParts(SPEC_TENANT, List.of(new MessagePart.ToolResultPart(
                ToolResult.success("call-1", "get_current_time", "10:15")))))
                .isEqualTo("e7e2cd7866c10043abc3ed828fc721c121b31102a8a4e0dac9e0592121323d22");
    }

    @Test
    void ofParts_anAudioPart_matchesThePublishedVector_beforeAndAfterItsTranscription() {
        MessagePart.Audio received = MessagePart.Audio.received("audio/ogg", "AwACAgQ", null, 4);
        String published = "1f3651c4ee122cf69ac3fa386ab0c6c40d8befa144e3a362de43f24d94fc149b";

        assertThat(AuditContentHash.ofParts(SPEC_TENANT, List.of(received))).isEqualTo(published);
        // What is learned later does not move the hash the message was audited under.
        assertThat(AuditContentHash.ofParts(SPEC_TENANT, List.of(received.transcribed("Mesa para cuatro",
                "a".repeat(64), 6200, 4)))).isEqualTo(published);
    }

    @Test
    void ofParts_isNotTheV1HashOfTheSameText() {
        // Two versions side by side: a text hashed as parts never collides with its v1 hash.
        assertThat(AuditContentHash.ofParts(tenantId, List.of(new MessagePart.Text("Hola"))))
                .isNotEqualTo(AuditContentHash.of(tenantId, "Hola"));
    }

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
