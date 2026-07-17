package dev.cauce.api.message;

import dev.cauce.orchestration.InboundMessageResult;
import java.util.UUID;

/**
 * Body of the {@code 202 Accepted} response to a posted message: the conversation the message
 * landed in (resolved or newly created), the persisted USER message, and the queued invocation.
 * The agent's reply is produced asynchronously; the client polls
 * {@code GET /v1/conversations/{id}/messages} for it and
 * {@code GET /v1/invocations/{invocationId}} for the processing status. An idempotent replay
 * returns the ids of the original ingest, including the same invocation id.
 */
public record PostMessageResponse(UUID conversationId, UUID messageId, UUID invocationId) {

    public static PostMessageResponse from(InboundMessageResult result) {
        return new PostMessageResponse(
                result.conversationId(), result.messageId(), result.invocationId());
    }
}
