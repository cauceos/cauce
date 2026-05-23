package dev.cauce.core.conversation;

import dev.cauce.core.UuidGenerator;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A conversation between an external user (patient, customer, citizen) and a specific
 * {@link dev.cauce.core.agent.Agent}, carried over a single communication channel.
 *
 * <p>Pure domain type: no persistence or framework dependencies. Immutable; created
 * via {@link #start} with status {@link ConversationStatus#OPEN} and a time-ordered
 * UUIDv7 id. The lifecycle transitions {@link #close}, {@link #escalate}, and
 * {@link #archive} do not mutate the instance: each returns a new {@code Conversation}
 * with the updated status and timestamp, or throws
 * {@link InvalidConversationTransitionException} if the transition is not allowed from
 * the current status.
 *
 * <p>{@code channelType} is a free-form String identifier (e.g. {@code "whatsapp"}).
 * The domain deliberately does not enumerate channels: the set of valid channels is
 * owned by the (not-yet-implemented) cauce-channels SPI, keeping the core free of
 * channel-specific knowledge (architectural invariant: channels are pluggable). Until
 * the SPI exists, the application service validates against a temporary set.
 *
 * <p>{@code externalIdentityRef} is the opaque reference to the external user within
 * the channel (a phone number for WhatsApp/voice, an email address for email, a
 * session id for web chat). The core does not interpret it; each channel adapter does.
 */
public final class Conversation {

    private final UUID id;
    private final UUID agentId;
    private final String channelType;
    private final String externalIdentityRef;
    private final ConversationStatus status;
    private final Instant startedAt;
    private final Instant lastMessageAt;
    private final Instant closedAt;    // null unless CLOSED (or archived after being closed)
    private final Instant escalatedAt; // null unless it was ever escalated
    private final Instant archivedAt;  // null unless ARCHIVED

    private Conversation(UUID id, UUID agentId, String channelType, String externalIdentityRef,
                         ConversationStatus status, Instant startedAt, Instant lastMessageAt,
                         Instant closedAt, Instant escalatedAt, Instant archivedAt) {
        this.id = id;
        this.agentId = agentId;
        this.channelType = channelType;
        this.externalIdentityRef = externalIdentityRef;
        this.status = status;
        this.startedAt = startedAt;
        this.lastMessageAt = lastMessageAt;
        this.closedAt = closedAt;
        this.escalatedAt = escalatedAt;
        this.archivedAt = archivedAt;
    }

    /**
     * Opens a new conversation for {@code agentId} on {@code channelType} with the
     * external user identified by {@code externalIdentityRef}. {@code lastMessageAt} is
     * initialised to the start instant; it will advance as messages arrive once
     * messages exist as an entity.
     */
    public static Conversation start(UUID agentId, String channelType, String externalIdentityRef) {
        Instant now = Instant.now();
        return new Conversation(
                UuidGenerator.newV7(),
                Objects.requireNonNull(agentId, "agentId"),
                requireText(channelType, "channelType"),
                requireText(externalIdentityRef, "externalIdentityRef"),
                ConversationStatus.OPEN,
                now,
                now,
                null,
                null,
                null);
    }

    /** Rebuilds a conversation from already-persisted state. For the persistence layer only. */
    public static Conversation rehydrate(UUID id, UUID agentId, String channelType,
                                         String externalIdentityRef, ConversationStatus status,
                                         Instant startedAt, Instant lastMessageAt, Instant closedAt,
                                         Instant escalatedAt, Instant archivedAt) {
        return new Conversation(
                Objects.requireNonNull(id, "id"),
                Objects.requireNonNull(agentId, "agentId"),
                Objects.requireNonNull(channelType, "channelType"),
                Objects.requireNonNull(externalIdentityRef, "externalIdentityRef"),
                Objects.requireNonNull(status, "status"),
                Objects.requireNonNull(startedAt, "startedAt"),
                Objects.requireNonNull(lastMessageAt, "lastMessageAt"),
                closedAt,    // nullable
                escalatedAt, // nullable
                archivedAt); // nullable
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.strip();
    }

    /**
     * Closes the conversation. Allowed from {@link ConversationStatus#OPEN} or
     * {@link ConversationStatus#ESCALATED} (a human resolves an escalation and closes).
     *
     * @return a new closed conversation with {@code closedAt} set to now
     * @throws InvalidConversationTransitionException if not OPEN or ESCALATED
     */
    public Conversation close() {
        if (status != ConversationStatus.OPEN && status != ConversationStatus.ESCALATED) {
            throw new InvalidConversationTransitionException(transitionError(ConversationStatus.CLOSED));
        }
        return new Conversation(id, agentId, channelType, externalIdentityRef,
                ConversationStatus.CLOSED, startedAt, lastMessageAt, Instant.now(), escalatedAt, archivedAt);
    }

    /**
     * Escalates the conversation to a human. Allowed only from
     * {@link ConversationStatus#OPEN}; there is no de-escalation.
     *
     * @return a new escalated conversation with {@code escalatedAt} set to now
     * @throws InvalidConversationTransitionException if not OPEN
     */
    public Conversation escalate() {
        if (status != ConversationStatus.OPEN) {
            throw new InvalidConversationTransitionException(transitionError(ConversationStatus.ESCALATED));
        }
        return new Conversation(id, agentId, channelType, externalIdentityRef,
                ConversationStatus.ESCALATED, startedAt, lastMessageAt, closedAt, Instant.now(), archivedAt);
    }

    /**
     * Archives the conversation. Allowed from any status except
     * {@link ConversationStatus#ARCHIVED} (an absorbing state). Preserves any existing
     * {@code closedAt}/{@code escalatedAt}.
     *
     * @return a new archived conversation with {@code archivedAt} set to now
     * @throws InvalidConversationTransitionException if already ARCHIVED
     */
    public Conversation archive() {
        if (status == ConversationStatus.ARCHIVED) {
            throw new InvalidConversationTransitionException(transitionError(ConversationStatus.ARCHIVED));
        }
        return new Conversation(id, agentId, channelType, externalIdentityRef,
                ConversationStatus.ARCHIVED, startedAt, lastMessageAt, closedAt, escalatedAt, Instant.now());
    }

    private String transitionError(ConversationStatus target) {
        return "Cannot transition Conversation %s from %s to %s".formatted(id, status, target);
    }

    public UUID id() {
        return id;
    }

    public UUID agentId() {
        return agentId;
    }

    public String channelType() {
        return channelType;
    }

    public String externalIdentityRef() {
        return externalIdentityRef;
    }

    public ConversationStatus status() {
        return status;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant lastMessageAt() {
        return lastMessageAt;
    }

    public Instant closedAt() {
        return closedAt;
    }

    public Instant escalatedAt() {
        return escalatedAt;
    }

    public Instant archivedAt() {
        return archivedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof Conversation other && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        // externalIdentityRef is intentionally omitted (personal data).
        return "Conversation[id=%s, agentId=%s, channelType=%s, status=%s]"
                .formatted(id, agentId, channelType, status);
    }
}
