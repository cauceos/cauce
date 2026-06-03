package dev.cauce.api.message;

import dev.cauce.orchestration.InboundMessageResult;
import java.util.UUID;

/**
 * Body of the {@code 202 Accepted} response to a posted message: the conversation the message
 * landed in (resolved or newly created) and the persisted USER message. The agent's reply is
 * produced asynchronously; the client polls {@code GET /v1/conversations/{id}/messages} for it.
 */
public record PostMessageResponse(UUID conversationId, UUID messageId) {

    public static PostMessageResponse from(InboundMessageResult result) {
        return new PostMessageResponse(result.conversationId(), result.messageId());
    }
}
