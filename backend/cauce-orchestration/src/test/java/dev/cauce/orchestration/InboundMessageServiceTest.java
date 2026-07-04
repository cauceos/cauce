package dev.cauce.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.cauce.core.conversation.Conversation;
import dev.cauce.core.message.Message;
import dev.cauce.core.message.MessageRole;
import dev.cauce.orchestration.events.InvocationRequested;
import dev.cauce.orchestration.exception.InvalidIdempotencyKeyException;
import dev.cauce.orchestration.persistence.IngestIdempotencyRecordEntity;
import dev.cauce.orchestration.persistence.IngestIdempotencyRecordMapper;
import dev.cauce.orchestration.persistence.IngestIdempotencyRecordRepository;
import dev.cauce.tenancy.ConversationService;
import dev.cauce.tenancy.MessageService;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;

class InboundMessageServiceTest {

    private ConversationService conversationService;
    private MessageService messageService;
    private PendingInvocationService pendingInvocationService;
    private IngestIdempotencyRecordRepository idempotencyRecordRepository;
    private ApplicationEventPublisher eventPublisher;
    private InboundMessageService service;

    @BeforeEach
    void setUp() {
        conversationService = mock(ConversationService.class);
        messageService = mock(MessageService.class);
        pendingInvocationService = mock(PendingInvocationService.class);
        idempotencyRecordRepository = mock(IngestIdempotencyRecordRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        service = new InboundMessageService(conversationService, messageService,
                pendingInvocationService, idempotencyRecordRepository,
                new IngestIdempotencyRecordMapper(), eventPublisher);
    }

    @Test
    void ingest_resolvesAppendsAndEnqueuesInOrder_returningTheThreeIds() {
        UUID agentId = UUID.randomUUID();
        Conversation conversation = Conversation.start(agentId, "api", "user-1");
        Message userMessage = Message.from(conversation.id(), MessageRole.USER, "Hola");
        PendingInvocation invocation =
                PendingInvocation.create(UUID.randomUUID(), conversation.id(), userMessage.id());

        when(conversationService.resolveOrStartConversation(agentId, "api", "user-1"))
                .thenReturn(conversation);
        when(messageService.appendMessage(conversation.id(), MessageRole.USER, "Hola"))
                .thenReturn(userMessage);
        when(pendingInvocationService.enqueueInvocation(conversation.id(), userMessage.id()))
                .thenReturn(invocation);

        InboundMessageResult result = service.ingest(agentId, "api", "user-1", "Hola");

        assertThat(result.conversationId()).isEqualTo(conversation.id());
        assertThat(result.messageId()).isEqualTo(userMessage.id());
        assertThat(result.invocationId()).isEqualTo(invocation.id());

        InOrder inOrder = inOrder(conversationService, messageService, pendingInvocationService);
        inOrder.verify(conversationService).resolveOrStartConversation(agentId, "api", "user-1");
        inOrder.verify(messageService).appendMessage(conversation.id(), MessageRole.USER, "Hola");
        inOrder.verify(pendingInvocationService).enqueueInvocation(conversation.id(), userMessage.id());
    }

    @Test
    void ingest_validMessage_publishesInvocationRequested() {
        UUID agentId = UUID.randomUUID();
        UUID owningTenantId = UUID.randomUUID();
        Conversation conversation = Conversation.start(agentId, "api", "user-1");
        Message userMessage = Message.from(conversation.id(), MessageRole.USER, "Hola");
        PendingInvocation invocation =
                PendingInvocation.create(owningTenantId, conversation.id(), userMessage.id());

        when(conversationService.resolveOrStartConversation(agentId, "api", "user-1"))
                .thenReturn(conversation);
        when(messageService.appendMessage(conversation.id(), MessageRole.USER, "Hola"))
                .thenReturn(userMessage);
        when(pendingInvocationService.enqueueInvocation(conversation.id(), userMessage.id()))
                .thenReturn(invocation);

        service.ingest(agentId, "api", "user-1", "Hola");

        ArgumentCaptor<InvocationRequested> captor =
                ArgumentCaptor.forClass(InvocationRequested.class);
        verify(eventPublisher).publishEvent(captor.capture());
        InvocationRequested event = captor.getValue();
        assertThat(event.invocationId()).isEqualTo(invocation.id());
        assertThat(event.tenantId()).isEqualTo(owningTenantId);
        assertThat(event.agentId()).isEqualTo(agentId);
        assertThat(event.conversationId()).isEqualTo(conversation.id());
        assertThat(event.messageId()).isEqualTo(userMessage.id());
        assertThat(event.occurredAt()).isNotNull();
    }

    @Test
    void ingest_forwardsTheCallerChannelType_soAdaptersAreNotTiedToApi() {
        UUID agentId = UUID.randomUUID();
        Conversation conversation = Conversation.start(agentId, "whatsapp", "+34600111222");
        Message userMessage = Message.from(conversation.id(), MessageRole.USER, "Hi");

        when(conversationService.resolveOrStartConversation(agentId, "whatsapp", "+34600111222"))
                .thenReturn(conversation);
        when(messageService.appendMessage(conversation.id(), MessageRole.USER, "Hi"))
                .thenReturn(userMessage);
        when(pendingInvocationService.enqueueInvocation(conversation.id(), userMessage.id()))
                .thenReturn(PendingInvocation.create(UUID.randomUUID(), conversation.id(), userMessage.id()));

        service.ingest(agentId, "whatsapp", "+34600111222", "Hi");

        verify(conversationService).resolveOrStartConversation(agentId, "whatsapp", "+34600111222");
    }

    // === IDEMPOTENCY ===

    @Test
    void ingest_withoutKey_neverTouchesIdempotencyStorage() {
        UUID agentId = UUID.randomUUID();
        Conversation conversation = Conversation.start(agentId, "api", "user-1");
        Message userMessage = Message.from(conversation.id(), MessageRole.USER, "Hola");

        when(conversationService.resolveOrStartConversation(agentId, "api", "user-1"))
                .thenReturn(conversation);
        when(messageService.appendMessage(conversation.id(), MessageRole.USER, "Hola"))
                .thenReturn(userMessage);
        when(pendingInvocationService.enqueueInvocation(conversation.id(), userMessage.id()))
                .thenReturn(PendingInvocation.create(UUID.randomUUID(), conversation.id(), userMessage.id()));

        service.ingest(agentId, "api", "user-1", "Hola", null);

        verifyNoInteractions(idempotencyRecordRepository);
        verify(eventPublisher).publishEvent(any(InvocationRequested.class));
    }

    @Test
    void ingest_replayedKey_returnsStoredResultWithoutSideEffects() {
        UUID agentId = UUID.randomUUID();
        IngestIdempotencyRecordEntity stored = storedRecord(agentId, "wamid-1");
        when(idempotencyRecordRepository.findByAgentIdAndIdempotencyKey(agentId, "wamid-1"))
                .thenReturn(Optional.of(stored));

        InboundMessageResult result = service.ingest(agentId, "api", "user-1", "Hola", "wamid-1");

        assertThat(result.conversationId()).isEqualTo(stored.getConversationId());
        assertThat(result.messageId()).isEqualTo(stored.getMessageId());
        assertThat(result.invocationId()).isEqualTo(stored.getInvocationId());
        verifyNoInteractions(conversationService, messageService, pendingInvocationService,
                eventPublisher);
        verify(idempotencyRecordRepository, never()).insertLockIfAbsent(any(), any(), anyString());
    }

    @Test
    void ingest_lostInsertRace_returnsWinnersResultWithoutSideEffects() {
        UUID agentId = UUID.randomUUID();
        Conversation conversation = Conversation.start(agentId, "api", "user-1");
        IngestIdempotencyRecordEntity winner = storedRecord(agentId, "wamid-1");

        when(idempotencyRecordRepository.findByAgentIdAndIdempotencyKey(agentId, "wamid-1"))
                .thenReturn(Optional.empty())   // fast-path miss: the winner had not committed yet
                .thenReturn(Optional.of(winner)); // re-read after losing the lock insert
        when(conversationService.resolveOrStartConversation(agentId, "api", "user-1"))
                .thenReturn(conversation);
        when(idempotencyRecordRepository.insertLockIfAbsent(any(), eq(agentId), eq("wamid-1")))
                .thenReturn(0);

        InboundMessageResult result = service.ingest(agentId, "api", "user-1", "Hola", "wamid-1");

        assertThat(result.conversationId()).isEqualTo(winner.getConversationId());
        assertThat(result.messageId()).isEqualTo(winner.getMessageId());
        assertThat(result.invocationId()).isEqualTo(winner.getInvocationId());
        verifyNoInteractions(messageService, pendingInvocationService, eventPublisher);
        verify(idempotencyRecordRepository, never()).recordResult(any(), any(), any(), any());
    }

    @Test
    void ingest_freshKey_recordsResultAndPublishesOnce() {
        UUID agentId = UUID.randomUUID();
        Conversation conversation = Conversation.start(agentId, "api", "user-1");
        Message userMessage = Message.from(conversation.id(), MessageRole.USER, "Hola");
        PendingInvocation invocation =
                PendingInvocation.create(UUID.randomUUID(), conversation.id(), userMessage.id());

        when(idempotencyRecordRepository.findByAgentIdAndIdempotencyKey(agentId, "wamid-1"))
                .thenReturn(Optional.empty());
        when(conversationService.resolveOrStartConversation(agentId, "api", "user-1"))
                .thenReturn(conversation);
        when(idempotencyRecordRepository.insertLockIfAbsent(any(), eq(agentId), eq("wamid-1")))
                .thenReturn(1);
        when(messageService.appendMessage(conversation.id(), MessageRole.USER, "Hola"))
                .thenReturn(userMessage);
        when(pendingInvocationService.enqueueInvocation(conversation.id(), userMessage.id()))
                .thenReturn(invocation);

        InboundMessageResult result = service.ingest(agentId, "api", "user-1", "Hola", "wamid-1");

        assertThat(result.invocationId()).isEqualTo(invocation.id());
        ArgumentCaptor<UUID> lockId = ArgumentCaptor.forClass(UUID.class);
        verify(idempotencyRecordRepository).insertLockIfAbsent(lockId.capture(), eq(agentId), eq("wamid-1"));
        verify(idempotencyRecordRepository).recordResult(
                lockId.getValue(), conversation.id(), userMessage.id(), invocation.id());
        verify(eventPublisher).publishEvent(any(InvocationRequested.class));
    }

    @Test
    void ingest_blankKey_throwsInvalidIdempotencyKey() {
        assertThatThrownBy(() ->
                service.ingest(UUID.randomUUID(), "api", "user-1", "Hola", "   "))
                .isInstanceOf(InvalidIdempotencyKeyException.class)
                .hasMessageContaining("blank");
        verifyNoInteractions(conversationService, messageService, pendingInvocationService,
                idempotencyRecordRepository, eventPublisher);
    }

    @Test
    void ingest_oversizedKey_throwsInvalidIdempotencyKey() {
        assertThatThrownBy(() ->
                service.ingest(UUID.randomUUID(), "api", "user-1", "Hola", "k".repeat(256)))
                .isInstanceOf(InvalidIdempotencyKeyException.class)
                .hasMessageContaining("255");
        verifyNoInteractions(conversationService, messageService, pendingInvocationService,
                idempotencyRecordRepository, eventPublisher);
    }

    private static IngestIdempotencyRecordEntity storedRecord(UUID agentId, String key) {
        return new IngestIdempotencyRecordEntity(UUID.randomUUID(), agentId, key,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Instant.now());
    }
}
