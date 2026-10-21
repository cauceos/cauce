package dev.cauce.core.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/**
 * The hash a tool-call audit payload carries instead of the tool's arguments or output (ADR 0005,
 * C5 — the rule of ADR 0004's PC-3: an audited payload holds ids, metadata and hashes, never
 * content an erasure must be able to reach).
 *
 * <pre>
 * tool_hash = lowercase_hex( SHA-256( UTF-8( "cauce-tool:v1:" + tenant_id + ":" + canonical_json(value) ) ) )
 * </pre>
 *
 * <p>{@code value} is the tool call's argument object or the tool result's output string, and
 * {@code canonical_json} is the chain format's canonical JSON with Unicode NFC normalisation (the
 * rules of the v2 entry scheme). The tenant binds the hash to one tenant's trail, as for
 * {@link AuditContentHash}; there is no secret salt, so anyone holding the business row — the
 * TOOL_CALL or TOOL_RESULT message — can recompute it. The definition is published, informatively,
 * in {@code docs/spec/audit-chain-format.md} §6.3.
 */
public final class AuditToolHash {

    private static final String DOMAIN_PREFIX = "cauce-tool:v1:";

    private AuditToolHash() {
    }

    /** The v1 tool hash of {@code value} (a JSON-representable value) within {@code tenantId}'s trail. */
    public static String of(UUID tenantId, Object value) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        String canonical = CanonicalJson.serialize(value, true);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(
                    (DOMAIN_PREFIX + tenantId + ":" + canonical).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory on every JVM", e);
        }
    }
}
