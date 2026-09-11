package dev.cauce.governance.audit;

import java.util.Objects;

/**
 * A point on a tenant's chain: a sequence number and the entry hash recorded there. Because
 * every entry hash commits to the whole prefix before it, a head is a compact commitment to
 * everything up to that sequence.
 *
 * <p>Two roles. As the <b>actual head</b> of a verification result it is what the caller can
 * keep; as an <b>anchor</b> supplied on the next verification it is what lets the verifier
 * tell a truncated chain from one that was never longer — the one comparison a database
 * cannot make against itself. It is also exactly what external time anchoring would publish,
 * so nothing here is invented for the anchor's sake.
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
