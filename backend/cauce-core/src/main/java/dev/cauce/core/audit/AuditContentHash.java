package dev.cauce.core.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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
 */
public final class AuditContentHash {

    private static final String DOMAIN_PREFIX = "cauce-content:v1:";

    private AuditContentHash() {
    }

    /** The v1 content hash of {@code content} within {@code tenantId}'s trail. */
    public static String of(UUID tenantId, String content) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(content, "content must not be null");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(
                    (DOMAIN_PREFIX + tenantId + ":" + content).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory on every JVM", e);
        }
    }
}
