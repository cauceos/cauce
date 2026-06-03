package dev.cauce.orchestration;

import java.util.UUID;

/**
 * Outcome of {@link InboundMessageService#ingest}: the conversation the message landed in
 * (resolved or freshly created), the persisted USER message, and the invocation enqueued for
 * the agent's reply. Callers surface whichever ids the channel needs (the REST endpoint returns
 * only {@code conversationId} and {@code messageId}).
 */
public record InboundMessageResult(UUID conversationId, UUID messageId, UUID invocationId) {
}
