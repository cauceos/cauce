package dev.cauce.orchestration.service;

import dev.cauce.core.message.Message;
import dev.cauce.core.message.MessageRole;
import dev.cauce.memory.conversation.ConversationRepository;
import dev.cauce.memory.message.MessageMapper;
import dev.cauce.memory.message.MessageRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists an orchestration error as a SYSTEM message in its own transaction.
 *
 * <p>Runs with {@link Propagation#REQUIRES_NEW} so the record commits independently of the
 * caller's transaction: when {@code OrchestratorService} re-throws the original provider
 * exception, its surrounding transaction rolls back, but the persisted error survives. This
 * lives in a separate bean because a self-invoked {@code @Transactional} method would not
 * pass through the Spring proxy and would silently join the caller's transaction.
 *
 * <p>The new transaction still gets its RLS tenant context: {@code RlsContextAspect} fires
 * on this {@code @Service} {@code @Transactional} method and re-applies the
 * {@code TenantContext} that the caller set.
 */
@Service
public class OrchestrationErrorRecorder {

    private final MessageRepository messageRepository;
    private final ConversationRepository conversationRepository;
    private final MessageMapper messageMapper;

    public OrchestrationErrorRecorder(MessageRepository messageRepository,
                                      ConversationRepository conversationRepository,
                                      MessageMapper messageMapper) {
        this.messageRepository = messageRepository;
        this.conversationRepository = conversationRepository;
        this.messageMapper = messageMapper;
    }

    /**
     * Appends a SYSTEM message with {@code content} to the conversation and advances its
     * {@code lastMessageAt}, committing in a brand-new transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Message recordError(UUID conversationId, String content) {
        Message message = Message.from(conversationId, MessageRole.SYSTEM, content);
        Message saved = messageMapper.toDomain(messageRepository.save(messageMapper.toEntity(message)));
        conversationRepository.touchLastMessageAt(conversationId, saved.createdAt());
        return saved;
    }
}
