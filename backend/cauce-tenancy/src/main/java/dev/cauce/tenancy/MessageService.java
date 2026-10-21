package dev.cauce.tenancy;

import dev.cauce.core.conversation.ConversationNotFoundException;
import dev.cauce.core.message.Message;
import dev.cauce.core.message.MessageRole;
import dev.cauce.memory.conversation.ConversationRepository;
import dev.cauce.memory.message.MessageEntity;
import dev.cauce.memory.message.MessageMapper;
import dev.cauce.memory.message.MessageRepository;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for appending and reading messages. Every method is tenant-scoped:
 * {@code RlsContextAspect} establishes the RLS context from {@code TenantContext} before
 * each transactional method runs, and Row-Level Security filters every query by the
 * visibility of the owning conversation.
 */
@Service
public class MessageService {

    private final MessageRepository messageRepository;
    private final ConversationRepository conversationRepository;
    private final MessageMapper messageMapper;
    private final Clock clock;

    public MessageService(MessageRepository messageRepository,
                          ConversationRepository conversationRepository,
                          MessageMapper messageMapper,
                          Clock clock) {
        this.messageRepository = messageRepository;
        this.conversationRepository = conversationRepository;
        this.messageMapper = messageMapper;
        this.clock = clock;
    }

    /**
     * Appends a message to a conversation and advances the conversation's
     * {@code lastMessageAt} to the new message's timestamp, atomically in one
     * transaction. The conversation must be visible under the current context (RLS);
     * otherwise it is reported as not found.
     */
    @Transactional
    public Message appendMessage(UUID conversationId, MessageRole role, String content) {
        conversationRepository.findById(conversationId).orElseThrow(() ->
                new ConversationNotFoundException("No conversation found for id " + conversationId));

        // Stamped from the database clock (ADR 0008): reply windows are measured against it.
        Message message = Message.from(conversationId, role, content).stampedAt(clock.instant());
        Message saved = messageMapper.toDomain(messageRepository.save(messageMapper.toEntity(message)));
        conversationRepository.touchLastMessageAt(conversationId, saved.createdAt());
        return saved;
    }

    /**
     * Lists up to {@code maxRows} of a conversation's messages ordered by id — for UUIDv7 ids
     * that is insertion order — starting after {@code afterId} when non-null (keyset
     * pagination). RLS filters by visibility. Context assembly does not use this: it reads
     * the full thread via {@code ConversationGateway} (ordered by {@code created_at}).
     *
     * <p>This is the public listing, so SYSTEM messages are left out (ADR 0005, C3): they are the
     * orchestrator's internal error records and can carry provider detail. The interlocutor's
     * view of a failure is the FALLBACK message, which is listed.
     */
    @Transactional
    public List<Message> listMessages(UUID conversationId, UUID afterId, int maxRows) {
        List<MessageEntity> page = afterId == null
                ? messageRepository.findByConversationIdAndRoleNotOrderByIdAsc(
                        conversationId, MessageRole.SYSTEM, Limit.of(maxRows))
                : messageRepository.findByConversationIdAndIdGreaterThanAndRoleNotOrderByIdAsc(
                        conversationId, afterId, MessageRole.SYSTEM, Limit.of(maxRows));
        return page.stream().map(messageMapper::toDomain).toList();
    }
}
