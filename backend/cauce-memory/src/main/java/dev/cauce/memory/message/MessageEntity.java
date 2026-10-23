package dev.cauce.memory.message;

import dev.cauce.core.message.MessageRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * JPA persistence mapping for a message row. Infrastructure detail of cauce-memory;
 * the domain type is {@link dev.cauce.core.message.Message}, converted by
 * {@link MessageMapper}.
 */
@Entity
@Table(name = "messages")
public class MessageEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "conversation_id", nullable = false, updatable = false)
    private UUID conversationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private MessageRole role;

    @Column(name = "content", nullable = false)
    private String content;

    /**
     * Structured tool payload for TOOL_CALL / TOOL_RESULT messages, stored as jsonb; null for
     * text messages. Shape: {tool_call_id, tool_name, input} for a call,
     * {tool_call_id, tool_name, output, is_error} for a result (built by {@link MessageMapper}).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tool_content")
    private Map<String, Object> toolContent;

    /** The internal failure type a FALLBACK message answers; null for every other role (V28). */
    @Column(name = "failure_type", length = 50)
    private String failureType;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /**
     * The message's position in its conversation, 1, 2, 3… assigned by the database on insert
     * (V44), never by a clock: what every read orders a conversation by.
     */
    @Column(name = "seq", insertable = false, updatable = false)
    private Long seq;

    protected MessageEntity() {
        // for JPA
    }

    /** Convenience constructor for a text message (USER / AGENT / SYSTEM): no tool content. */
    public MessageEntity(UUID id, UUID conversationId, MessageRole role, String content,
                         Instant createdAt) {
        this(id, conversationId, role, content, null, createdAt);
    }

    public MessageEntity(UUID id, UUID conversationId, MessageRole role, String content,
                         Map<String, Object> toolContent, Instant createdAt) {
        this(id, conversationId, role, content, toolContent, null, createdAt);
    }

    public MessageEntity(UUID id, UUID conversationId, MessageRole role, String content,
                         Map<String, Object> toolContent, String failureType, Instant createdAt) {
        this.id = id;
        this.conversationId = conversationId;
        this.role = role;
        this.content = content;
        this.toolContent = toolContent;
        this.failureType = failureType;
        this.createdAt = createdAt;
    }

    public String getFailureType() {
        return failureType;
    }

    public UUID getId() {
        return id;
    }

    public UUID getConversationId() {
        return conversationId;
    }

    public MessageRole getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public Map<String, Object> getToolContent() {
        return toolContent;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** Null on an entity not yet read back from the database (the trigger assigns it on insert). */
    public Long getSeq() {
        return seq;
    }
}
