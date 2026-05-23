package dev.cauce.core.conversation;

import com.github.f4b6a3.uuid.UuidCreator;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A conversation between an external user (patient, customer, citizen) and a specific
 * {@link dev.cauce.core.agent.Agent}, carried over a single communication channel.
 *
 * <p>Pure domain type: no persistence or framework dependencies. Immutable; created
 * via {@link #start} with status {@link ConversationStatus#OPEN} and a time-ordered
 * UUIDv7 id. Status transitions (close, escalate, archive) arrive in a later commit.
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
    private final Instant closedAt; // null while not CLOSED

    private Conversation(UUID id, UUID agentId, String channelType, String externalIdentityRef,
                         ConversationStatus status, Instant startedAt, Instant lastMessageAt,
                         Instant closedAt) {
        this.id = id;
        this.agentId = agentId;
        this.channelType = channelType;
        this.externalIdentityRef = externalIdentityRef;
        this.status = status;
        this.startedAt = startedAt;
        this.lastMessageAt = lastMessageAt;
        this.closedAt = closedAt;
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
                UuidCreator.getTimeOrderedEpoch(),
                Objects.requireNonNull(agentId, "agentId"),
                requireText(channelType, "channelType"),
                requireText(externalIdentityRef, "externalIdentityRef"),
                ConversationStatus.OPEN,
                now,
                now,
                null);
    }

    /** Rebuilds a conversation from already-persisted state. For the persistence layer only. */
    public static Conversation rehydrate(UUID id, UUID agentId, String channelType,
                                         String externalIdentityRef, ConversationStatus status,
                                         Instant startedAt, Instant lastMessageAt, Instant closedAt) {
        return new Conversation(
                Objects.requireNonNull(id, "id"),
                Objects.requireNonNull(agentId, "agentId"),
                Objects.requireNonNull(channelType, "channelType"),
                Objects.requireNonNull(externalIdentityRef, "externalIdentityRef"),
                Objects.requireNonNull(status, "status"),
                Objects.requireNonNull(startedAt, "startedAt"),
                Objects.requireNonNull(lastMessageAt, "lastMessageAt"),
                closedAt); // nullable: null while OPEN
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.strip();
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
