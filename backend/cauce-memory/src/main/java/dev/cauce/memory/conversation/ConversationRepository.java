package dev.cauce.memory.conversation;

import dev.cauce.core.conversation.ConversationStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link ConversationEntity}. Derived queries only for now.
 * Result sets are filtered by the conversations Row-Level Security policy according to
 * the active tenant context.
 */
public interface ConversationRepository extends JpaRepository<ConversationEntity, UUID> {

    List<ConversationEntity> findByAgentId(UUID agentId);

    List<ConversationEntity> findByAgentIdAndStatus(UUID agentId, ConversationStatus status);

    /**
     * Finds a single conversation for a given (agent, channel, external user, status)
     * tuple. Used to locate the active (OPEN) conversation when routing an incoming
     * message to the right thread.
     */
    Optional<ConversationEntity> findByAgentIdAndChannelTypeAndExternalIdentityRefAndStatus(
            UUID agentId, String channelType, String externalIdentityRef, ConversationStatus status);
}
