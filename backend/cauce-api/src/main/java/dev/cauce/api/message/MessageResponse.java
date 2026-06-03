package dev.cauce.api.message;

import dev.cauce.core.message.Message;
import dev.cauce.core.message.MessageRole;
import java.time.Instant;
import java.util.UUID;

/**
 * API representation of a {@link Message}. Decoupled from the domain type so the wire contract
 * can evolve independently. {@code role} serialises as its name ({@code USER}/{@code AGENT}/
 * {@code SYSTEM}); other fields are snake_case via the global Jackson naming strategy.
 */
public record MessageResponse(
        UUID id,
        MessageRole role,
        String content,
        Instant createdAt) {

    public static MessageResponse from(Message message) {
        return new MessageResponse(message.id(), message.role(), message.content(), message.createdAt());
    }
}
