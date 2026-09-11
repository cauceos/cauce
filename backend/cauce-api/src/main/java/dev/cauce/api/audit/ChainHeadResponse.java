package dev.cauce.api.audit;

import dev.cauce.governance.audit.ChainHead;

/**
 * A point on the chain: a sequence number and the entry hash recorded there. Every entry
 * hash commits to the whole prefix before it, so this is a compact commitment to the chain up
 * to that sequence.
 *
 * <p>It is the value a reference held outside the database would be built from — what
 * external anchoring publishes. The endpoint reports it and does not take one back: comparing
 * the chain against an earlier head is deliberately not part of this contract.
 */
public record ChainHeadResponse(long sequenceNumber, String entryHash) {

    static ChainHeadResponse from(ChainHead head) {
        return head == null ? null : new ChainHeadResponse(head.sequenceNumber(), head.entryHash());
    }
}
