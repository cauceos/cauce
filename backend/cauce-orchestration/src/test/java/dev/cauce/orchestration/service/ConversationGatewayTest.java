package dev.cauce.orchestration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.cauce.core.agent.AgentNotFoundException;
import dev.cauce.core.agent.AgentStatus;
import dev.cauce.core.conversation.ConversationNotFoundException;
import dev.cauce.core.conversation.ConversationStatus;
import dev.cauce.core.message.Message;
import dev.cauce.core.message.MessageNotFoundException;
import dev.cauce.core.message.MessageRole;
import dev.cauce.memory.agent.AgentEntity;
import dev.cauce.memory.agent.AgentMapper;
import dev.cauce.memory.agent.AgentRepository;
import dev.cauce.memory.conversation.ConversationEntity;
import dev.cauce.memory.conversation.ConversationRepository;
import dev.cauce.memory.message.MessageEntity;
import dev.cauce.memory.message.MessageMapper;
import dev.cauce.memory.message.MessageRepository;
import dev.cauce.orchestration.exception.InvalidTriggerMessageException;
import dev.cauce.orchestration.service.ConversationGateway.LoadedConversation;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ConversationGatewayTest {

    private static final String MODEL = "claude-sonnet-4-7";
    private static final String PROVIDER = "anthropic";

    private final UUID conversationId = UUID.randomUUID();
    private final UUID agentId = UUID.randomUUID();
    private final UUID tenantId = UUID.randomUUID();
    private final UUID triggerId = UUID.randomUUID();
    private final Instant now = Instant.now();

    private ConversationRepository conversationRepository;
    private MessageRepository messageRepository;
    private AgentRepository agentRepository;
    private ConversationGateway gateway;

    @BeforeEach
    void setUp() {
        conversationRepository = Mockito.mock(ConversationRepository.class);
        messageRepository = Mockito.mock(MessageRepository.class);
        agentRepository = Mockito.mock(AgentRepository.class);
        gateway = new ConversationGateway(conversationRepository, messageRepository,
                new MessageMapper(), agentRepository, new AgentMapper());
    }

    @Test
    void load_happyPath_returnsAgentAndChronologicalMessages() {
        stubConversation();
        stubHistory(triggerEntity(MessageRole.USER, "Hola"));
        stubAgent();

        LoadedConversation loaded = gateway.load(conversationId, triggerId);

        assertThat(loaded.agent().id()).isEqualTo(agentId);
        assertThat(loaded.agent().modelName()).isEqualTo(MODEL);
        assertThat(loaded.messages()).extracting(Message::content).containsExactly("Hola");
    }

    @Test
    void load_whenConversationNotFound_throwsConversationNotFound() {
        when(conversationRepository.findById(conversationId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> gateway.load(conversationId, triggerId))
                .isInstanceOf(ConversationNotFoundException.class);
    }

    @Test
    void load_whenTriggerMessageNotInConversation_throwsMessageNotFound() {
        stubConversation();
        stubHistory(); // empty history: trigger absent

        assertThatThrownBy(() -> gateway.load(conversationId, triggerId))
                .isInstanceOf(MessageNotFoundException.class);
    }

    @Test
    void load_whenTriggerIsNotUserRole_throwsInvalidTriggerMessage() {
        stubConversation();
        stubHistory(triggerEntity(MessageRole.AGENT, "previous agent reply"));

        assertThatThrownBy(() -> gateway.load(conversationId, triggerId))
                .isInstanceOf(InvalidTriggerMessageException.class);
    }

    @Test
    void load_whenAgentNotFound_throwsAgentNotFound() {
        stubConversation();
        stubHistory(triggerEntity(MessageRole.USER, "Hola"));
        when(agentRepository.findById(agentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> gateway.load(conversationId, triggerId))
                .isInstanceOf(AgentNotFoundException.class);
    }

    @Test
    void append_persistsMessageAndAdvancesConversation() {
        when(messageRepository.save(any(MessageEntity.class))).thenAnswer(call -> call.getArgument(0));
        Message message = Message.from(conversationId, MessageRole.AGENT, "Reply");

        Message saved = gateway.append(message);

        assertThat(saved.role()).isEqualTo(MessageRole.AGENT);
        assertThat(saved.content()).isEqualTo("Reply");
        verify(messageRepository).save(argThat(entity ->
                entity.getRole() == MessageRole.AGENT && entity.getContent().equals("Reply")));
        verify(conversationRepository).touchLastMessageAt(eq(conversationId), any());
    }

    // === fixtures ===

    private void stubConversation() {
        when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(
                new ConversationEntity(conversationId, agentId, "whatsapp", "+34600000000",
                        ConversationStatus.OPEN, now, now, null, null, null)));
    }

    private void stubHistory(MessageEntity... history) {
        when(messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId))
                .thenReturn(List.of(history));
    }

    private void stubAgent() {
        when(agentRepository.findById(agentId)).thenReturn(Optional.of(
                new AgentEntity(agentId, tenantId, "DentalBot", "You are helpful.", PROVIDER, MODEL,
                        0.7, 4096, AgentStatus.ACTIVE, now, now)));
    }

    private MessageEntity triggerEntity(MessageRole role, String content) {
        return new MessageEntity(triggerId, conversationId, role, content, now);
    }
}
