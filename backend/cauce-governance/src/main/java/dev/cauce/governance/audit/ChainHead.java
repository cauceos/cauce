package dev.cauce.governance.audit;

import java.util.Objects;

/**
 * A point on a tenant's chain: a sequence number and the entry hash recorded there. Because
 * every entry hash commits to the whole prefix before it, a head is a compact commitment to
 * everything up to that sequence — which is why "publishing the head hash is enough" is the
 * whole of what external anchoring needs (ADR 0003). The verifier reports it; it does not
 * compare against one.
 */
public record ChainHead(long sequenceNumber, String entryHash) {

    public ChainHead {
        Objects.requireNonNull(entryHash, "entryHash must not be null");
        if (sequenceNumber < 1) {
            throw new IllegalArgumentException("sequenceNumber must be >= 1");
        }
        if (entryHash.isBlank()) {
            throw new IllegalArgumentException("entryHash must not be blank");
        }
    }
}
