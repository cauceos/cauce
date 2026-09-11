package dev.cauce.governance.audit;

import dev.cauce.core.UuidGenerator;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One entry of the append-only audit ledger: a drained outbox row plus its per-tenant,
 * contiguous {@code sequenceNumber} (starting at 1) and its hash-chain fields. Entries are
 * immutable facts — the database enforces it (UPDATE/DELETE are revoked from the runtime
 * role), the domain type merely mirrors it.
 *
 * <p>Chain fields (all written on the drain INSERT, never by a later UPDATE):
 * {@code payloadHash} is the persisted hash of the payload document — the only form of the
 * payload the chain commits to, so verification survives a payload redaction;
 * {@code prevHash} links to the previous entry's {@code entryHash} (the tenant-derived
 * genesis hash for the tenant's first entry); {@code entryHash} is the hash over the explicit
 * preimage of {@code hashScheme}, which names the scheme the entry was written under
 * ({@code "v1"} or {@code "v2"} — see {@link AuditChainHasher}) and is what the verifier
 * branches on. All four are null only on rows drained before the chain unit (pre-chain rows
 * — the verifier treats them as an unverifiable prefix, never backfilled).
 *
 * <p>{@code payload} is null when the owner redacted it post hoc (an operation the runtime
 * role cannot perform); {@code signature} is RESERVED for the signing unit (always null).
 *
 * <p>Pure domain type: no persistence or framework dependencies.
 */
public record AuditLogEntry(UUID id,
                            UUID tenantId,
                            long sequenceNumber,
                            UUID outboxId,
                            String eventType,
                            Map<String, Object> payload,
                            Instant drainedAt,
                            String payloadHash,
                            String prevHash,
                            String entryHash,
                            String hashScheme,
                            String signature) {

    public AuditLogEntry {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(outboxId, "outboxId must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(drainedAt, "drainedAt must not be null");
        if (eventType.isBlank()) {
            throw new IllegalArgumentException("eventType must not be blank");
        }
        if (sequenceNumber < 1) {
            throw new IllegalArgumentException("sequenceNumber must be >= 1");
        }
        if (payload != null) {
            payload = Map.copyOf(payload);
        }
    }

    /**
     * Numbers {@code outboxEntry} into the ledger as sequence {@code sequenceNumber} of its
     * tenant, chained: {@code prevHash} is the tenant's current head hash (or genesis) and
     * the hashes were computed by {@link AuditChainHasher} over this entry's {@code id} and
     * {@code drainedAt} — which is why the caller passes both in, minted with {@link #mintId()}
     * and {@link #mintDrainedAt()} so the stored values equal the hashed ones. ({@code id} is
     * in the v2 preimage, so it can no longer be generated after the hash; {@code drainedAt}
     * is truncated because PostgreSQL keeps microseconds and an untruncated instant would read
     * back different from what was hashed.)
     */
    public static AuditLogEntry chained(UUID id, AuditOutboxEntry outboxEntry,
                                        long sequenceNumber, Instant drainedAt,
                                        String payloadHash, String prevHash,
                                        String entryHash, String hashScheme) {
        Objects.requireNonNull(outboxEntry, "outboxEntry must not be null");
        Objects.requireNonNull(payloadHash, "payloadHash must not be null");
        Objects.requireNonNull(prevHash, "prevHash must not be null");
        Objects.requireNonNull(entryHash, "entryHash must not be null");
        Objects.requireNonNull(hashScheme, "hashScheme must not be null");
        return new AuditLogEntry(id, outboxEntry.tenantId(), sequenceNumber,
                outboxEntry.id(), outboxEntry.eventType(), outboxEntry.payload(), drainedAt,
                payloadHash, prevHash, entryHash, hashScheme, null);
    }

    /** An entry id, minted before hashing because the v2 preimage commits to it. */
    public static UUID mintId() {
        return UuidGenerator.newV7();
    }

    /** A drain timestamp truncated to what PostgreSQL round-trips (microseconds). */
    public static Instant mintDrainedAt() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
