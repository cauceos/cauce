package dev.cauce.core.conversation;

import dev.cauce.core.UuidGenerator;
import dev.cauce.core.identity.Identity;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A conversation between an interlocutor (patient, customer, citizen) and a specific
 * {@link dev.cauce.core.agent.Agent}, entered through a single channel instance.
 *
 * <p>Pure domain type: no persistence or framework dependencies. Immutable; created
 * via {@link #start} with status {@link ConversationStatus#OPEN} and a time-ordered
 * UUIDv7 id. The lifecycle transitions {@link #close}, {@link #escalate}, and
 * {@link #archive} do not mutate the instance: each returns a new {@code Conversation}
 * with the updated status and timestamp, or throws
 * {@link InvalidConversationTransitionException} if the transition is not allowed from
 * the current status.
 *
 * <p>The interlocutor is an {@link Identity} (ADR 0004, decision 1), referenced by
 * {@code identityId}; {@code channelConfigId} is the channel instance the thread came in
 * through (the door, not the person), {@code null} for the built-in {@code api} channel,
 * which has no config. The conversation carries no channel type and no copy of the
 * identity's value (ADR 0004, PC-2): the channel is the identity's and the config's.
 */
public final class Conversation {

    private final UUID id;
    private final UUID agentId;
    private final UUID identityId;
    private final UUID channelConfigId; // null for the api channel
    private final ConversationStatus status;
    private final Instant startedAt;
    private final Instant lastMessageAt;
    private final Instant closedAt;    // null unless CLOSED (or archived after being closed)
    private final Instant escalatedAt; // null unless it was ever escalated
    private final Instant archivedAt;  // null unless ARCHIVED

    private Conversation(UUID id, UUID agentId, UUID identityId, UUID channelConfigId,
                         ConversationStatus status, Instant startedAt, Instant lastMessageAt,
                         Instant closedAt, Instant escalatedAt, Instant archivedAt) {
        this.id = id;
        this.agentId = agentId;
        this.identityId = identityId;
        this.channelConfigId = channelConfigId;
        this.status = status;
        this.startedAt = startedAt;
        this.lastMessageAt = lastMessageAt;
        this.closedAt = closedAt;
        this.escalatedAt = escalatedAt;
        this.archivedAt = archivedAt;
    }

    /**
     * Opens a new conversation of {@code agentId} with the interlocutor {@code identity},
     * entered through the channel instance {@code channelConfigId} ({@code null} for the
     * {@code api} channel). {@code lastMessageAt} is initialised to the start instant.
     */
    public static Conversation start(UUID agentId, Identity identity, UUID channelConfigId) {
        Objects.requireNonNull(identity, "identity");
        Instant now = Instant.now();
        return new Conversation(
                UuidGenerator.newV7(),
                Objects.requireNonNull(agentId, "agentId"),
                identity.id(),
                channelConfigId,
                ConversationStatus.OPEN,
                now,
                now,
                null,
                null,
                null);
    }

    /** Rebuilds a conversation from already-persisted state. For the persistence layer only. */
    public static Conversation rehydrate(UUID id, UUID agentId, UUID identityId,
                                         UUID channelConfigId, ConversationStatus status,
                                         Instant startedAt, Instant lastMessageAt, Instant closedAt,
                                         Instant escalatedAt, Instant archivedAt) {
        return new Conversation(
                Objects.requireNonNull(id, "id"),
                Objects.requireNonNull(agentId, "agentId"),
                Objects.requireNonNull(identityId, "identityId"),
                channelConfigId, // nullable: api
                Objects.requireNonNull(status, "status"),
                Objects.requireNonNull(startedAt, "startedAt"),
                Objects.requireNonNull(lastMessageAt, "lastMessageAt"),
                closedAt,    // nullable
                escalatedAt, // nullable
                archivedAt); // nullable
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
        return new Conversation(id, agentId, identityId, channelConfigId,
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
        return new Conversation(id, agentId, identityId, channelConfigId,
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
        return new Conversation(id, agentId, identityId, channelConfigId,
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

    public UUID identityId() {
        return identityId;
    }

    /** The channel instance the thread came in through; empty for the {@code api} channel. */
    public Optional<UUID> channelConfigId() {
        return Optional.ofNullable(channelConfigId);
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
        // The identity's value is intentionally omitted (personal data); its id is opaque.
        return "Conversation[id=%s, agentId=%s, identityId=%s, status=%s]"
                .formatted(id, agentId, identityId, status);
    }
}
