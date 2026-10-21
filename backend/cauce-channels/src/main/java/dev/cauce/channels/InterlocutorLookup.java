package dev.cauce.channels;

import dev.cauce.core.identity.Identity;
import dev.cauce.core.message.MessageRole;
import dev.cauce.memory.identity.IdentityMapper;
import dev.cauce.memory.identity.IdentityRepository;
import dev.cauce.memory.message.MessageEntity;
import dev.cauce.memory.message.MessageRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads the interlocutor a reply goes back to: the identity a conversation references. Its own
 * transactional bean so the read runs under RLS ({@code RlsContextAspect} establishes the
 * context from {@code TenantContext}) on the outbound executor thread, where delivery
 * re-establishes the dispatching tenant. cauce-channels does not depend on cauce-tenancy, so
 * it reads through the cauce-memory repository, as {@link ChannelConfigService} does for
 * configs.
 */
@Service
public class InterlocutorLookup {

    private final IdentityRepository identityRepository;
    private final IdentityMapper identityMapper;
    private final MessageRepository messageRepository;

    public InterlocutorLookup(IdentityRepository identityRepository, IdentityMapper identityMapper,
                              MessageRepository messageRepository) {
        this.identityRepository = identityRepository;
        this.identityMapper = identityMapper;
        this.messageRepository = messageRepository;
    }

    /**
     * When the interlocutor last wrote in {@code conversationId} (its newest USER message), under
     * RLS — what a channel with a reply window checks (ADR 0006, W4).
     */
    @Transactional(readOnly = true)
    public Optional<Instant> lastMessageAt(UUID conversationId) {
        return messageRepository.findFirstByConversationIdAndRoleOrderByCreatedAtDesc(conversationId, MessageRole.USER)
                .map(MessageEntity::getCreatedAt);
    }

    /**
     * When {@code identityId} last wrote through channel config {@code channelConfigId}, in any of
     * its conversations there, under RLS: the reply window of a proactive message (ADR 0007, B9).
     */
    @Transactional(readOnly = true)
    public Optional<Instant> lastMessageAt(UUID identityId, UUID channelConfigId) {
        return messageRepository.findLastUserMessageAt(identityId, channelConfigId);
    }

    /** The identity, if it is visible under the current tenant context (RLS). */
    @Transactional(readOnly = true)
    public Optional<Identity> find(UUID identityId) {
        return identityRepository.findById(identityId).map(identityMapper::toDomain);
    }
}
