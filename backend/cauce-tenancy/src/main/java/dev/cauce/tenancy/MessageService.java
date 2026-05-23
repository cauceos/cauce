package dev.cauce.tenancy;

import dev.cauce.core.conversation.ConversationNotFoundException;
import dev.cauce.core.message.Message;
import dev.cauce.core.message.MessageRole;
import dev.cauce.memory.conversation.ConversationRepository;
import dev.cauce.memory.message.MessageMapper;
import dev.cauce.memory.message.MessageRepository;
import java.util.List;
import java.util.UUID;
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

    public MessageService(MessageRepository messageRepository,
                          ConversationRepository conversationRepository,
                          MessageMapper messageMapper) {
        this.messageRepository = messageRepository;
        this.conversationRepository = conversationRepository;
        this.messageMapper = messageMapper;
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

        Message message = Message.from(conversationId, role, content);
        Message saved = messageMapper.toDomain(messageRepository.save(messageMapper.toEntity(message)));
        conversationRepository.touchLastMessageAt(conversationId, saved.createdAt());
        return saved;
    }

    /** Lists a conversation's messages in chronological order; RLS filters by visibility. */
    @Transactional
    public List<Message> listMessages(UUID conversationId) {
        return messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId).stream()
                .map(messageMapper::toDomain)
                .toList();
    }
}
