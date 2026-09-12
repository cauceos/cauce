package dev.cauce.governance.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The audit hash schemes: SHA-256 (JDK {@link MessageDigest}, no extra dependency) over the
 * {@link CanonicalJson} form of an EXPLICIT preimage, hex-encoded (64 chars). Two schemes
 * coexist by design — {@link #SCHEME_V1} for entries already written, {@link #SCHEME_V2} for
 * everything written from now on. Every method takes the scheme it must apply; the caller
 * gets it from the row being verified ({@code hash_scheme}) or from {@link #CURRENT_SCHEME}
 * when chaining a new one. Nothing is ever re-hashed: a v1 entry stays v1 forever.
 *
 * <p><b>The normative specification of both preimages is
 * {@code docs/spec/audit-chain-format.md}</b> — field by field, with the exact encodings and
 * test vectors that {@code AuditFormatSpecVectorsTest} pins. It is written so a third party
 * can verify a chain without reading this class, which is the whole point of publishing it;
 * this javadoc gives the intent, not the bytes, so the two cannot drift apart.
 *
 * <p>In short: <b>v1</b> commits to eight fields; <b>v2</b> adds {@code id} and makes two
 * corrections that v1 needed to be reproducible elsewhere. {@code id} enters the preimage
 * because under v1 it was the only column outside the hash, so it could be altered without
 * detection. Timestamps move to FIXED microsecond precision, because
 * {@link Instant#toString()} omits the fraction when it is zero and varies its length
 * otherwise — the same instant could serialise two ways. Strings are normalised to Unicode
 * NFC, so two byte sequences with identical meaning cannot produce different hashes; that
 * applies to the payload document too, which is why the scheme is threaded through
 * {@link #payloadHash}.
 *
 * <p>Neither preimage contains the raw payload (only its persisted {@code payload_hash}, so
 * the chain still verifies after a payload redaction) nor the signature (signing is
 * downstream of {@code entry_hash}; adding it later re-hashes nothing). The scheme identifier
 * is committed inside the preimage and persisted per row ({@code hash_scheme}), so the two
 * can never be confused for one another.
 *
 * <p>The genesis {@code prev_hash} of each tenant's first chained entry is derived from the
 * tenant id, binding the chain to its tenant: a segment transplanted from another tenant's
 * chain fails verification at genesis already. It is deliberately NOT versioned — see
 * {@link #genesisHash}.
 */
@Component
public class AuditChainHasher {

    /** The original scheme. Frozen: entries written under it are verified under it, forever. */
    public static final String SCHEME_V1 = "v1";

    /** The current scheme: v1 plus {@code id}, fixed-precision timestamps and NFC. */
    public static final String SCHEME_V2 = "v2";

    /**
     * The scheme new entries are born under. A code constant on purpose: the scheme is a
     * property of the code that wrote the row, not an operator choice and not chain state.
     * Making it configurable would only add a way to write weaker entries from a build that
     * can write stronger ones. Two instances on different versions may write the same chain
     * — each row carries its own {@code hash_scheme}, the head row's {@code FOR UPDATE}
     * serialises the writes, and the {@code prev_hash} link is opaque to the scheme — so a
     * chain may legitimately read v1, v2, v1, v2 and still verify.
     */
    public static final String CURRENT_SCHEME = SCHEME_V2;

    /**
     * Frozen at the v1 spelling on purpose, for BOTH schemes. The genesis hash is a
     * tenant-binding domain separator, not a preimage: it contains no timestamp and no free
     * text, so none of the v2 corrections apply to it. Versioning it would change the
     * expected {@code prev_hash} of every existing chain's first entry — a history rewrite —
     * and would force the verifier to know the first row's scheme before reading it.
     */
    private static final String GENESIS_PREFIX = "cauce-audit-genesis:v1:";

    /** ISO-8601 with EXACTLY six fractional digits and a {@code Z} offset. */
    private static final DateTimeFormatter INSTANT_MICROS =
            new DateTimeFormatterBuilder().appendInstant(6).toFormatter();

    /** Whether this hasher can verify entries written under {@code scheme}. */
    public boolean supports(String scheme) {
        return SCHEME_V1.equals(scheme) || SCHEME_V2.equals(scheme);
    }

    /**
     * Hash of the payload document itself; the only form of it the chain ever commits to.
     * Scheme-dependent because v2 normalises strings to NFC: hashing an existing v1 payload
     * under v2 rules would declare honest rows broken.
     */
    public String payloadHash(String scheme, Map<String, Object> payload) {
        Objects.requireNonNull(payload, "payload must not be null");
        return sha256Hex(CanonicalJson.serialize(payload, normalizesUnicode(scheme)));
    }

    /** The {@code prev_hash} of a tenant's first chained entry, in every scheme. */
    public String genesisHash(UUID tenantId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        return sha256Hex(GENESIS_PREFIX + tenantId);
    }

    /**
     * The entry hash over {@code scheme}'s preimage — see the class javadoc. {@code id} is
     * part of the preimage under v2 only; v1 ignores it (it is still required, so callers
     * cannot forget it when they move to v2).
     */
    public String entryHash(String scheme, UUID id, UUID tenantId, long sequenceNumber,
                            UUID outboxId, String eventType, Instant drainedAt,
                            String payloadHash, String prevHash) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(outboxId, "outboxId must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(drainedAt, "drainedAt must not be null");
        Objects.requireNonNull(payloadHash, "payloadHash must not be null");
        Objects.requireNonNull(prevHash, "prevHash must not be null");
        boolean v2 = normalizesUnicode(scheme);
        Map<String, Object> preimage = new TreeMap<>();
        preimage.put("drained_at", v2 ? INSTANT_MICROS.format(drainedAt) : drainedAt.toString());
        preimage.put("event_type", eventType);
        preimage.put("outbox_id", outboxId.toString());
        preimage.put("payload_hash", payloadHash);
        preimage.put("prev_hash", prevHash);
        preimage.put("scheme", scheme);
        preimage.put("sequence_number", sequenceNumber);
        preimage.put("tenant_id", tenantId.toString());
        if (v2) {
            preimage.put("id", id.toString());
        }
        return sha256Hex(CanonicalJson.serialize(preimage, v2));
    }

    /** Doubles as the scheme guard: an unknown scheme is never silently treated as v1. */
    private static boolean normalizesUnicode(String scheme) {
        Objects.requireNonNull(scheme, "scheme must not be null");
        return switch (scheme) {
            case SCHEME_V1 -> false;
            case SCHEME_V2 -> true;
            default -> throw new IllegalArgumentException("unknown hash scheme: " + scheme);
        };
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
