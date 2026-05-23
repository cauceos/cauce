package dev.cauce.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.cauce.core.conversation.ConversationNotFoundException;
import dev.cauce.core.conversation.ConversationStatus;
import dev.cauce.core.message.Message;
import dev.cauce.core.message.MessageRole;
import dev.cauce.memory.conversation.ConversationEntity;
import dev.cauce.memory.conversation.ConversationRepository;
import dev.cauce.memory.message.MessageEntity;
import dev.cauce.memory.message.MessageMapper;
import dev.cauce.memory.message.MessageRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class MessageServiceTest {

    private MessageRepository messageRepository;
    private ConversationRepository conversationRepository;
    private MessageService service;

    @BeforeEach
    void setUp() {
        messageRepository = Mockito.mock(MessageRepository.class);
        conversationRepository = Mockito.mock(ConversationRepository.class);
        service = new MessageService(messageRepository, conversationRepository, new MessageMapper());
        when(messageRepository.save(any(MessageEntity.class))).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void appendMessage_whenConversationNotFound_throwsConversationNotFound() {
        UUID conversationId = UUID.randomUUID();
        when(conversationRepository.findById(conversationId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.appendMessage(conversationId, MessageRole.USER, "Hi"))
                .isInstanceOf(ConversationNotFoundException.class);
    }

    @Test
    void appendMessage_whenValid_persistsAndReturnsMessage() {
        UUID conversationId = UUID.randomUUID();
        when(conversationRepository.findById(conversationId))
                .thenReturn(Optional.of(conversation(conversationId)));

        Message message = service.appendMessage(conversationId, MessageRole.USER, "Hello");

        assertThat(message.conversationId()).isEqualTo(conversationId);
        assertThat(message.role()).isEqualTo(MessageRole.USER);
        assertThat(message.content()).isEqualTo("Hello");
    }

    @Test
    void appendMessage_advancesConversationLastMessageAt_toMessageTimestamp() {
        UUID conversationId = UUID.randomUUID();
        when(conversationRepository.findById(conversationId))
                .thenReturn(Optional.of(conversation(conversationId)));

        Message message = service.appendMessage(conversationId, MessageRole.AGENT, "Reply");

        verify(conversationRepository).touchLastMessageAt(conversationId, message.createdAt());
    }

    @Test
    void listMessages_mapsRepositoryResultsToDomain() {
        UUID conversationId = UUID.randomUUID();
        Message m1 = Message.from(conversationId, MessageRole.USER, "first");
        Message m2 = Message.from(conversationId, MessageRole.AGENT, "second");
        MessageMapper mapper = new MessageMapper();
        when(messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId))
                .thenReturn(List.of(mapper.toEntity(m1), mapper.toEntity(m2)));

        List<Message> messages = service.listMessages(conversationId);

        assertThat(messages).extracting(Message::id).containsExactly(m1.id(), m2.id());
    }

    private static ConversationEntity conversation(UUID id) {
        Instant now = Instant.now();
        return new ConversationEntity(id, UUID.randomUUID(), "whatsapp", "+34612345678",
                ConversationStatus.OPEN, now, now, null);
    }
}
