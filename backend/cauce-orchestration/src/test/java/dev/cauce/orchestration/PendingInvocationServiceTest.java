package dev.cauce.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import dev.cauce.core.agent.AgentStatus;
import dev.cauce.core.conversation.ConversationNotFoundException;
import dev.cauce.core.conversation.ConversationStatus;
import dev.cauce.core.message.MessageNotFoundException;
import dev.cauce.core.message.MessageRole;
import dev.cauce.memory.agent.AgentEntity;
import dev.cauce.memory.agent.AgentRepository;
import dev.cauce.memory.conversation.ConversationEntity;
import dev.cauce.memory.conversation.ConversationRepository;
import dev.cauce.memory.message.MessageEntity;
import dev.cauce.memory.message.MessageRepository;
import dev.cauce.orchestration.persistence.PendingInvocationEntity;
import dev.cauce.orchestration.persistence.PendingInvocationMapper;
import dev.cauce.orchestration.persistence.PendingInvocationRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class PendingInvocationServiceTest {

    private final UUID agentId = UUID.randomUUID();
    private final UUID tenantId = UUID.randomUUID();
    private final UUID conversationId = UUID.randomUUID();

    private PendingInvocationRepository pendingInvocationRepository;
    private ConversationRepository conversationRepository;
    private MessageRepository messageRepository;
    private AgentRepository agentRepository;
    private PendingInvocationService service;

    @BeforeEach
    void setUp() {
        pendingInvocationRepository = Mockito.mock(PendingInvocationRepository.class);
        conversationRepository = Mockito.mock(ConversationRepository.class);
        messageRepository = Mockito.mock(MessageRepository.class);
        agentRepository = Mockito.mock(AgentRepository.class);
        service = new PendingInvocationService(pendingInvocationRepository, conversationRepository,
                messageRepository, agentRepository, new PendingInvocationMapper());
        when(pendingInvocationRepository.save(any(PendingInvocationEntity.class)))
                .thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void enqueueInvocation_whenConversationNotFound_throwsConversationNotFound() {
        when(conversationRepository.findById(conversationId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.enqueueInvocation(conversationId, UUID.randomUUID()))
                .isInstanceOf(ConversationNotFoundException.class);
    }

    @Test
    void enqueueInvocation_whenMessageNotInConversation_throwsMessageNotFound() {
        UUID triggerMessageId = UUID.randomUUID();
        when(conversationRepository.findById(conversationId))
                .thenReturn(Optional.of(conversationEntity()));
        when(messageRepository.findByIdAndConversationId(triggerMessageId, conversationId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.enqueueInvocation(conversationId, triggerMessageId))
                .isInstanceOf(MessageNotFoundException.class);
    }

    @Test
    void enqueueInvocation_whenValid_persistsAndReturnsPendingOwnedByAgentTenant() {
        UUID triggerMessageId = UUID.randomUUID();
        when(conversationRepository.findById(conversationId))
                .thenReturn(Optional.of(conversationEntity()));
        when(messageRepository.findByIdAndConversationId(triggerMessageId, conversationId))
                .thenReturn(Optional.of(messageEntity(triggerMessageId)));
        when(agentRepository.findById(agentId)).thenReturn(Optional.of(agentEntity()));

        PendingInvocation invocation = service.enqueueInvocation(conversationId, triggerMessageId);

        assertThat(invocation.status()).isEqualTo(PendingInvocationStatus.PENDING);
        assertThat(invocation.tenantId()).isEqualTo(tenantId);
        assertThat(invocation.conversationId()).isEqualTo(conversationId);
        assertThat(invocation.triggerMessageId()).isEqualTo(triggerMessageId);
        assertThat(invocation.attemptCount()).isZero();
    }

    @Test
    void getPendingInvocation_whenFound_returnsMapped() {
        PendingInvocation domain = PendingInvocation.create(tenantId, conversationId, UUID.randomUUID());
        PendingInvocationMapper mapper = new PendingInvocationMapper();
        when(pendingInvocationRepository.findById(domain.id()))
                .thenReturn(Optional.of(mapper.toEntity(domain)));

        Optional<PendingInvocation> found = service.getPendingInvocation(domain.id());

        assertThat(found).isPresent();
        assertThat(found.get().id()).isEqualTo(domain.id());
    }

    @Test
    void getPendingInvocation_whenMissing_returnsEmpty() {
        UUID id = UUID.randomUUID();
        when(pendingInvocationRepository.findById(id)).thenReturn(Optional.empty());

        assertThat(service.getPendingInvocation(id)).isEmpty();
    }

    @Test
    void listPendingInvocationsForConversation_mapsResults() {
        PendingInvocation a = PendingInvocation.create(tenantId, conversationId, UUID.randomUUID());
        PendingInvocation b = PendingInvocation.create(tenantId, conversationId, UUID.randomUUID());
        PendingInvocationMapper mapper = new PendingInvocationMapper();
        when(pendingInvocationRepository.findByConversationId(conversationId))
                .thenReturn(List.of(mapper.toEntity(a), mapper.toEntity(b)));

        List<PendingInvocation> result = service.listPendingInvocationsForConversation(conversationId);

        assertThat(result).extracting(PendingInvocation::id).containsExactly(a.id(), b.id());
    }

    private ConversationEntity conversationEntity() {
        Instant now = Instant.now();
        return new ConversationEntity(conversationId, agentId, "whatsapp", "+34600000000",
                ConversationStatus.OPEN, now, now, null, null, null);
    }

    private MessageEntity messageEntity(UUID id) {
        return new MessageEntity(id, conversationId, MessageRole.USER, "I need help", Instant.now());
    }

    private AgentEntity agentEntity() {
        Instant now = Instant.now();
        return new AgentEntity(agentId, tenantId, "DentalBot", "prompt", "anthropic",
                "claude-sonnet-4-7", 0.7, 4096, AgentStatus.ACTIVE, now, now);
    }
}
