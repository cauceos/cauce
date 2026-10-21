package dev.cauce.core.message;

import dev.cauce.core.UuidGenerator;
import dev.cauce.core.tool.ToolCall;
import dev.cauce.core.tool.ToolContent;
import dev.cauce.core.tool.ToolResult;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A single message within a {@link dev.cauce.core.conversation.Conversation}.
 *
 * <p>Pure domain type: no persistence or framework dependencies. Immutable — once
 * created a message is never edited or deleted; there are no mutators. Created via
 * {@link #from} (text), {@link #toolCall}, or {@link #toolResult}, each minting a
 * time-ordered UUIDv7 id.
 *
 * <p>{@code content} is opaque UTF-8 plain text, preserved verbatim (not trimmed) and
 * never blank. Text messages (USER, AGENT, SYSTEM) carry only content. Tool messages
 * (TOOL_CALL, TOOL_RESULT) additionally carry structured {@link ToolContent}; their
 * content is a human-readable rendering (the tool name for a call, the output for a
 * result), while the machine-readable payload lives in {@link #toolContent()}.
 */
public final class Message {

    private static final int TO_STRING_CONTENT_LIMIT = 50;

    private final UUID id;
    private final UUID conversationId;
    private final MessageRole role;
    private final String content;
    private final ToolContent toolContent;
    private final String failureType; // present exactly for FALLBACK
    private final Instant createdAt;

    private Message(UUID id, UUID conversationId, MessageRole role, String content,
                    ToolContent toolContent, String failureType, Instant createdAt) {
        this.id = id;
        this.conversationId = conversationId;
        this.role = role;
        this.content = content;
        this.toolContent = toolContent;
        this.failureType = failureType;
        this.createdAt = createdAt;
    }

    /** Creates a new text message authored by {@code role} (USER, AGENT, or SYSTEM). */
    public static Message from(UUID conversationId, MessageRole role, String content) {
        return create(conversationId, role, content, null);
    }

    /** Creates a new TOOL_CALL message wrapping the agent's request to run a tool. */
    public static Message toolCall(UUID conversationId, ToolCall call) {
        Objects.requireNonNull(call, "call");
        return create(conversationId, MessageRole.TOOL_CALL, call.toolName(), call);
    }

    /** Creates a new TOOL_RESULT message wrapping the output of executing a tool call. */
    public static Message toolResult(UUID conversationId, ToolResult result) {
        Objects.requireNonNull(result, "result");
        return create(conversationId, MessageRole.TOOL_RESULT, renderContent(result), result);
    }

    /**
     * Creates a new FALLBACK message: the platform's {@code text} to the interlocutor after a
     * terminal failure of type {@code failureType} (ADR 0005, C3). The type is the internal
     * failure taxonomy's name, held as a String so the core does not depend on it; the API maps
     * it to the public failure reason.
     */
    public static Message fallback(UUID conversationId, String text, String failureType) {
        return create(conversationId, MessageRole.FALLBACK, text, null, failureType);
    }

    private static Message create(UUID conversationId, MessageRole role, String content,
                                  ToolContent toolContent) {
        return create(conversationId, role, content, toolContent, null);
    }

    private static Message create(UUID conversationId, MessageRole role, String content,
                                  ToolContent toolContent, String failureType) {
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(role, "role");
        validate(role, content, toolContent, failureType);
        return new Message(UuidGenerator.newV7(), conversationId, role, content, toolContent,
                failureType, Instant.now());
    }

    /** Rebuilds a text message from persisted state. For the persistence layer only. */
    public static Message rehydrate(UUID id, UUID conversationId, MessageRole role,
                                    String content, Instant createdAt) {
        return rehydrate(id, conversationId, role, content, null, createdAt);
    }

    /**
     * Rebuilds a message (text or tool) from persisted state. For the persistence layer only.
     */
    public static Message rehydrate(UUID id, UUID conversationId, MessageRole role,
                                    String content, ToolContent toolContent, Instant createdAt) {
        return rehydrate(id, conversationId, role, content, toolContent, null, createdAt);
    }

    /**
     * Rebuilds any message, a FALLBACK included, from persisted state. For the persistence
     * layer only.
     */
    public static Message rehydrate(UUID id, UUID conversationId, MessageRole role,
                                    String content, ToolContent toolContent, String failureType,
                                    Instant createdAt) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(createdAt, "createdAt");
        validate(role, content, toolContent, failureType);
        return new Message(id, conversationId, role, content, toolContent, failureType, createdAt);
    }

    private static String renderContent(ToolResult result) {
        return result.output().isBlank() ? "[empty result]" : result.output();
    }

    /**
     * Enforces the message invariants: content is always present (non-blank), tool content is
     * present exactly for the tool roles, matching the role's kind, and a failure type is
     * present exactly for FALLBACK.
     */
    private static void validate(MessageRole role, String content, ToolContent toolContent,
                                 String failureType) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content must not be blank");
        }
        if (role == MessageRole.FALLBACK) {
            if (failureType == null || failureType.isBlank()) {
                throw new IllegalArgumentException("FALLBACK requires a failure type");
            }
        } else if (failureType != null) {
            throw new IllegalArgumentException(role + " must not carry a failure type");
        }
        switch (role) {
            case TOOL_CALL -> {
                if (!(toolContent instanceof ToolCall)) {
                    throw new IllegalArgumentException("TOOL_CALL requires ToolCall tool content");
                }
            }
            case TOOL_RESULT -> {
                if (!(toolContent instanceof ToolResult)) {
                    throw new IllegalArgumentException(
                            "TOOL_RESULT requires ToolResult tool content");
                }
            }
            default -> {
                if (toolContent != null) {
                    throw new IllegalArgumentException(role + " must not carry tool content");
                }
            }
        }
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

    /** The structured tool payload, present only for TOOL_CALL / TOOL_RESULT messages. */
    public Optional<ToolContent> toolContent() {
        return Optional.ofNullable(toolContent);
    }

    /** The failure type a FALLBACK message answers; empty for every other role. */
    public Optional<String> failureType() {
        return Optional.ofNullable(failureType);
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
