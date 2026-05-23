package dev.cauce.core.message;

import dev.cauce.core.UuidGenerator;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A single message within a {@link dev.cauce.core.conversation.Conversation}.
 *
 * <p>Pure domain type: no persistence or framework dependencies. Immutable — once
 * created a message is never edited or deleted; there are no mutators. Created via
 * {@link #from} with a time-ordered UUIDv7 id.
 *
 * <p>{@code content} is opaque UTF-8 plain text and is preserved verbatim (not
 * trimmed): leading/trailing whitespace can be significant in message bodies. It must
 * not be blank, since an empty message carries no meaning.
 */
public final class Message {

    private static final int TO_STRING_CONTENT_LIMIT = 50;

    private final UUID id;
    private final UUID conversationId;
    private final MessageRole role;
    private final String content;
    private final Instant createdAt;

    private Message(UUID id, UUID conversationId, MessageRole role, String content, Instant createdAt) {
        this.id = id;
        this.conversationId = conversationId;
        this.role = role;
        this.content = content;
        this.createdAt = createdAt;
    }

    /** Creates a new message authored by {@code role} in the given conversation. */
    public static Message from(UUID conversationId, MessageRole role, String content) {
        return new Message(
                UuidGenerator.newV7(),
                Objects.requireNonNull(conversationId, "conversationId"),
                Objects.requireNonNull(role, "role"),
                requireContent(content),
                Instant.now());
    }

    /** Rebuilds a message from already-persisted state. For the persistence layer only. */
    public static Message rehydrate(UUID id, UUID conversationId, MessageRole role,
                                    String content, Instant createdAt) {
        return new Message(
                Objects.requireNonNull(id, "id"),
                Objects.requireNonNull(conversationId, "conversationId"),
                Objects.requireNonNull(role, "role"),
                Objects.requireNonNull(content, "content"),
                Objects.requireNonNull(createdAt, "createdAt"));
    }

    private static String requireContent(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content must not be blank");
        }
        return content; // preserved verbatim, not trimmed
    }

    public UUID id() {
        return id;
    }

    public UUID conversationId() {
        return conversationId;
    }

    public MessageRole role() {
        return role;
    }

    public String content() {
        return content;
    }

    public Instant createdAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof Message other && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        // content is truncated and never fully exposed (it may be large or sensitive).
        String preview = content.length() <= TO_STRING_CONTENT_LIMIT
                ? content
                : content.substring(0, TO_STRING_CONTENT_LIMIT) + "...";
        return "Message[id=%s, conversationId=%s, role=%s, content=%s]"
                .formatted(id, conversationId, role, preview);
    }
}
