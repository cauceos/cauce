package dev.cauce.governance.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The audit hash scheme, version {@code v1}: SHA-256 (JDK {@link MessageDigest}, no extra
 * dependency) over the {@link CanonicalJson} form, hex-encoded (64 chars).
 *
 * <p>The {@code entry_hash} preimage is a canonical JSON object of EXPLICIT fields —
 * {@code drained_at} (ISO-8601 instant, microsecond precision), {@code event_type},
 * {@code outbox_id}, {@code payload_hash}, {@code prev_hash}, {@code scheme},
 * {@code sequence_number}, {@code tenant_id} — never the raw payload (only its persisted
 * {@code payload_hash}, so the chain still verifies after a payload redaction) and never the
 * signature (signing is downstream of {@code entry_hash}; adding it later re-hashes
 * nothing). The scheme identifier is committed inside the preimage and persisted per row
 * ({@code hash_scheme}), so the scheme can evolve without ambiguity.
 *
 * <p>The genesis {@code prev_hash} of each tenant's first chained entry is derived from the
 * tenant id, binding the chain to its tenant: a segment transplanted from another tenant's
 * chain fails verification at genesis already.
 */
@Component
public class AuditChainHasher {

    /** Persisted in {@code hash_scheme} and committed inside every v1 preimage. */
    public static final String SCHEME = "v1";

    private static final String GENESIS_PREFIX = "cauce-audit-genesis:" + SCHEME + ":";

    /** Hash of the payload document itself; the only form of it the chain ever commits to. */
    public String payloadHash(Map<String, Object> payload) {
        Objects.requireNonNull(payload, "payload must not be null");
        return sha256Hex(CanonicalJson.serialize(payload));
    }

    /** The {@code prev_hash} of a tenant's first chained entry. */
    public String genesisHash(UUID tenantId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        return sha256Hex(GENESIS_PREFIX + tenantId);
    }

    /** The v1 entry hash over the explicit preimage fields — see the class javadoc. */
    public String entryHash(UUID tenantId, long sequenceNumber, UUID outboxId, String eventType,
                            Instant drainedAt, String payloadHash, String prevHash) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(outboxId, "outboxId must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(drainedAt, "drainedAt must not be null");
        Objects.requireNonNull(payloadHash, "payloadHash must not be null");
        Objects.requireNonNull(prevHash, "prevHash must not be null");
        Map<String, Object> preimage = new TreeMap<>();
        preimage.put("drained_at", drainedAt.toString());
        preimage.put("event_type", eventType);
        preimage.put("outbox_id", outboxId.toString());
        preimage.put("payload_hash", payloadHash);
        preimage.put("prev_hash", prevHash);
        preimage.put("scheme", SCHEME);
        preimage.put("sequence_number", sequenceNumber);
        preimage.put("tenant_id", tenantId.toString());
        return sha256Hex(CanonicalJson.serialize(preimage));
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory on every JVM", e);
        }
    }
}
