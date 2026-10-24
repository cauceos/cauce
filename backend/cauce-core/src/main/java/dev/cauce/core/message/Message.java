package dev.cauce.core.message;

import dev.cauce.core.UuidGenerator;
import dev.cauce.core.tool.ToolCall;
import dev.cauce.core.tool.ToolContent;
import dev.cauce.core.tool.ToolResult;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * A single message within a {@link dev.cauce.core.conversation.Conversation}.
 *
 * <p>Pure domain type: no persistence or framework dependencies. Immutable — once
 * created a message is never edited or deleted; there are no mutators. Created via
 * {@link #from} (text), {@link #toolCall}, or {@link #toolResult}, each minting a
 * time-ordered UUIDv7 id.
 *
 * <p>A message is an ordered list of {@link MessagePart parts} (ADR 0004, decision 2), its one
 * source of truth: a text message (USER, AGENT, SYSTEM, FALLBACK) is text parts, a tool message
 * (TOOL_CALL, TOOL_RESULT) its one tool part. {@link #content()} and {@link #toolContent()} are
 * derived from the parts, never stored: the text, verbatim (not trimmed) and never blank, and for
 * a tool message a human-readable rendering of its part (the tool name for a call, the output for
 * a result). A voice note (ADR 0012) reads as its transcript, or {@link #UNTRANSCRIBED_AUDIO}
 * until it has one; only a USER message carries one.
 */
public final class Message {

    /** What an audio part reads as before it is transcribed: never blank, never the model's to read. */
    public static final String UNTRANSCRIBED_AUDIO = "[voice note]";

    private static final int TO_STRING_CONTENT_LIMIT = 50;

    private final UUID id;
    private final UUID conversationId;
    private final MessageRole role;
    private final List<MessagePart> parts;
    private final String failureType; // present exactly for FALLBACK
    private final Instant createdAt;

    private Message(UUID id, UUID conversationId, MessageRole role, List<MessagePart> parts,
                    String failureType, Instant createdAt) {
        this.id = id;
        this.conversationId = conversationId;
        this.role = role;
        this.parts = parts;
        this.failureType = failureType;
        this.createdAt = createdAt;
    }

    /** Creates a new text message authored by {@code role} (USER, AGENT, or SYSTEM). */
    public static Message from(UUID conversationId, MessageRole role, String content) {
        return create(conversationId, role, content, null);
    }

    /**
     * Creates a new message authored by {@code role} from its {@code parts}, in order: how a
     * channel's message that is not only text (a voice note) is ingested.
     */
    public static Message fromParts(UUID conversationId, MessageRole role, List<MessagePart> parts) {
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(role, "role");
        List<MessagePart> copy = List.copyOf(Objects.requireNonNull(parts, "parts"));
        validate(role, copy, null);
        return new Message(UuidGenerator.newV7(), conversationId, role, copy, null, Instant.now());
    }

    /** Creates a new TOOL_CALL message wrapping the agent's request to run a tool. */
    public static Message toolCall(UUID conversationId, ToolCall call) {
        Objects.requireNonNull(call, "call");
        return create(conversationId, MessageRole.TOOL_CALL, null, call);
    }

    /** Creates a new TOOL_RESULT message wrapping the output of executing a tool call. */
    public static Message toolResult(UUID conversationId, ToolResult result) {
        Objects.requireNonNull(result, "result");
        return create(conversationId, MessageRole.TOOL_RESULT, null, result);
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
        List<MessagePart> parts = partsOf(content, toolContent);
        validate(role, parts, failureType);
        return new Message(UuidGenerator.newV7(), conversationId, role, parts, failureType, Instant.now());
    }

    /** Rebuilds a text message from persisted state. For the persistence layer only. */
    public static Message rehydrate(UUID id, UUID conversationId, MessageRole role,
                                    String content, Instant createdAt) {
        return rehydrate(id, conversationId, role, content, null, createdAt);
    }

    /**
     * Rebuilds a message (text or tool) from persisted state: a tool message from its tool
     * content alone, {@code content} being its rendering. For the persistence layer only.
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
        return rehydrateParts(id, conversationId, role, partsOf(content, toolContent), failureType, createdAt);
    }

    /** Rebuilds any message from its persisted parts, in order. For the persistence layer only. */
    public static Message rehydrateParts(UUID id, UUID conversationId, MessageRole role,
                                         List<MessagePart> parts, String failureType, Instant createdAt) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(parts, "parts");
        Objects.requireNonNull(createdAt, "createdAt");
        List<MessagePart> copy = List.copyOf(parts);
        validate(role, copy, failureType);
        return new Message(id, conversationId, role, copy, failureType, createdAt);
    }

    /**
     * This message with its creation time set to {@code createdAt}. The persistence choke points
     * stamp each message from the database clock as they store it (ADR 0008): a channel's reply
     * window measures the interlocutor's last message against that clock, and the thread is read
     * back in this order.
     */
    public Message stampedAt(Instant createdAt) {
        return new Message(id, conversationId, role, parts, failureType,
                Objects.requireNonNull(createdAt, "createdAt"));
    }

    /**
     * This message with its voice notes having learned what their bytes held (ADR 0012, V2): the
     * one change a stored message admits. The parts keep their kinds and order, and an audio part
     * keeps its media type and provider reference — what the message was audited under; only what
     * the download and the transcription learn (size, the bytes' hash, duration, transcript) is new.
     *
     * @throws IllegalArgumentException if anything else would change
     */
    public Message withParts(List<MessagePart> learned) {
        List<MessagePart> copy = List.copyOf(Objects.requireNonNull(learned, "learned"));
        if (copy.size() != parts.size()) {
            throw new IllegalArgumentException("a message keeps its parts: " + parts.size() + ", not " + copy.size());
        }
        for (int i = 0; i < copy.size(); i++) {
            MessagePart before = parts.get(i);
            MessagePart after = copy.get(i);
            boolean same = before instanceof MessagePart.Audio was && after instanceof MessagePart.Audio now
                    ? was.mediaType().equals(now.mediaType()) && was.providerReference().equals(now.providerReference())
                    : before.equals(after);
            if (!same) {
                throw new IllegalArgumentException("part " + i + " of a stored message may only learn its transcript");
            }
        }
        validate(role, copy, failureType);
        return new Message(id, conversationId, role, copy, failureType, createdAt);
    }

    private static String renderContent(ToolResult result) {
        return result.output().isBlank() ? "[empty result]" : result.output();
    }

    /**
     * The parts of a message given as text or, for a tool message, as its tool content: the tool
     * part alone (its text is a rendering of it), else one text part.
     */
    private static List<MessagePart> partsOf(String content, ToolContent toolContent) {
        if (toolContent instanceof ToolCall call) {
            return List.of(new MessagePart.ToolCallPart(call));
        }
        if (toolContent instanceof ToolResult result) {
            return List.of(new MessagePart.ToolResultPart(result));
        }
        if (content == null) {
            throw new IllegalArgumentException("content must not be blank");
        }
        return List.of(new MessagePart.Text(content));
    }

    /** The text of {@code parts}: each text verbatim, a tool part's rendering, one per line. */
    private static String render(List<MessagePart> parts) {
        return parts.stream().map(part -> switch (part) {
            case MessagePart.Text text -> text.text();
            case MessagePart.Audio audio -> audio.transcriptText().orElse(UNTRANSCRIBED_AUDIO);
            case MessagePart.ToolCallPart toolCall -> toolCall.call().toolName();
            case MessagePart.ToolResultPart toolResult -> renderContent(toolResult.result());
        }).collect(Collectors.joining("\n"));
    }

    /**
     * Enforces the message invariants: at least one part and a non-blank text; a tool role is
     * exactly its one tool part, of the role's kind; USER is text and audio parts, every other role
     * text parts only (an agent does not speak, ADR 0012, V6); a failure type is present exactly
     * for FALLBACK.
     */
    private static void validate(MessageRole role, List<MessagePart> parts, String failureType) {
        if (parts.isEmpty() || render(parts).isBlank()) {
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
                if (parts.size() != 1 || !(parts.get(0) instanceof MessagePart.ToolCallPart)) {
                    throw new IllegalArgumentException("TOOL_CALL requires ToolCall tool content");
                }
            }
            case TOOL_RESULT -> {
                if (parts.size() != 1 || !(parts.get(0) instanceof MessagePart.ToolResultPart)) {
                    throw new IllegalArgumentException(
                            "TOOL_RESULT requires ToolResult tool content");
                }
            }
            default -> {
                if (parts.stream().anyMatch(part -> part instanceof MessagePart.ToolCallPart
                        || part instanceof MessagePart.ToolResultPart)) {
                    throw new IllegalArgumentException(role + " must not carry tool content");
                }
                if (role != MessageRole.USER && parts.stream().anyMatch(MessagePart.Audio.class::isInstance)) {
                    throw new IllegalArgumentException(role + " must not carry audio");
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

    /** The message's parts, in order: its one source of truth. */
    public List<MessagePart> parts() {
        return parts;
    }

    /** The message's text, derived from its parts (a tool message's is a rendering of its part). */
    public String content() {
        return render(parts);
    }

    /** The structured tool payload, present only for TOOL_CALL / TOOL_RESULT messages. */
    public Optional<ToolContent> toolContent() {
        return switch (parts.get(0)) {
            case MessagePart.ToolCallPart toolCall -> Optional.of(toolCall.call());
            case MessagePart.ToolResultPart toolResult -> Optional.of(toolResult.result());
            case MessagePart.Text ignored -> Optional.empty();
            case MessagePart.Audio ignored -> Optional.empty();
        };
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
        String content = content();
        String preview = content.length() <= TO_STRING_CONTENT_LIMIT
                ? content
                : content.substring(0, TO_STRING_CONTENT_LIMIT) + "...";
        return "Message[id=%s, conversationId=%s, role=%s, content=%s]"
                .formatted(id, conversationId, role, preview);
    }
}
