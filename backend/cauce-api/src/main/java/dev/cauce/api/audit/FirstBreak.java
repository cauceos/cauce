package dev.cauce.api.audit;

/**
 * Where and how a tenant's audit chain first breaks: the sequence number of the earliest
 * defective entry and its public {@link BreakClassification}. Present only on a
 * {@link ChainStatus#BROKEN} response; {@code null} when the chain is valid.
 */
public record FirstBreak(long sequenceNumber, BreakClassification classification) {
}
