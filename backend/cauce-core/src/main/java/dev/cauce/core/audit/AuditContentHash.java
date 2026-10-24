package dev.cauce.core.audit;

import dev.cauce.core.message.MessagePart;
import dev.cauce.core.message.MessageParts;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The content-binding hash of the audit trail: SHA-256 hex (64 chars) over
 * {@code "cauce-content:v1:" + tenantId + ":" + content}. An audit payload carries this
 * hash INSTEAD of the content, proving "this exact content was processed" without the
 * append-only ledger ever storing the (erasable) text — anyone holding the business row can
 * recompute and compare; nobody can recover the text from the ledger.
 *
 * <p>Domain-separated and tenant-bound: generic rainbow tables don't apply, and equal
 * content never correlates across tenants. Deliberately NO secret salt — a secret would
 * break third-party verifiability and requires key management (the signing unit's
 * territory). Known caveat: low-entropy content remains brute-force re-identifiable from
 * its hash; whether the hash satisfies an erasure obligation is the customer's legal
 * judgment, never asserted here.
 *
 * <p>Two versions coexist and nothing recorded is recomputed (ADR 0004, decision 2). v1 hashes a
 * text: a business document, an agent's prompt, and the messages audited before message parts. v2
 * hashes a message's parts, {@code "cauce-content:v2:" + tenantId + ":" + canonical_json(parts)},
 * the parts in their one JSON form ({@link MessageParts}) and the canonical JSON with Unicode NFC
 * normalisation; a payload hashed so says {@code content_hash_version: 2}. Both are published,
 * informatively, in {@code docs/spec/audit-chain-format.md} §6.3.
 */
public final class AuditContentHash {

    private static final String DOMAIN_PREFIX = "cauce-content:v1:";
    private static final String PARTS_DOMAIN_PREFIX = "cauce-content:v2:";

    /** The version a payload names next to a {@link #ofParts} hash. */
    public static final int PARTS_VERSION = 2;

    private AuditContentHash() {
    }

    /** The v1 content hash of {@code content} within {@code tenantId}'s trail. */
    public static String of(UUID tenantId, String content) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(content, "content must not be null");
        return sha256Hex(DOMAIN_PREFIX + tenantId + ":" + content);
    }

    /** The v2 content hash of a message's {@code parts} within {@code tenantId}'s trail. */
    public static String ofParts(UUID tenantId, List<MessagePart> parts) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(parts, "parts must not be null");
        return sha256Hex(PARTS_DOMAIN_PREFIX + tenantId + ":"
                + CanonicalJson.serialize(MessageParts.toJson(parts), true));
    }

    private static String sha256Hex(String preimage) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(preimage.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory on every JVM", e);
        }
    }
}
