package dev.cauce.api.audit;

import dev.cauce.governance.audit.ChainHead;

/**
 * A point on the chain: a sequence number and the entry hash recorded there. Every entry
 * hash commits to the whole prefix before it, so this is a compact commitment to the chain up
 * to that sequence.
 *
 * <p>As {@code head} on a response it is what a caller should keep. Passed back on a later
 * verification as {@code expected_head_sequence} / {@code expected_head_hash}, it is the only
 * thing that lets the verifier tell a truncated chain from one that was never longer.
 */
public record ChainHeadResponse(long sequenceNumber, String entryHash) {

    static ChainHeadResponse from(ChainHead head) {
        return head == null ? null : new ChainHeadResponse(head.sequenceNumber(), head.entryHash());
    }
}
