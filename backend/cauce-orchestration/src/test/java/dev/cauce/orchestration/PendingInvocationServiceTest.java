package dev.cauce.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class PendingInvocationServiceTest {

    private final UUID agentId = UUID.randomUUID();
    private final UUID tenantId = UUID.randomUUID();
    private final UUID conversationId = UUID.randomUUID();

    private PendingInvocationRepository pendingInvocationRepository;
    private ConversationRepository conversationRepository;
    private MessageRepository messageRepository;
    private AgentRepository agentRepository;
    private PendingInvocationMapper mapper;
    private PendingInvocationService service;

    @BeforeEach
    void setUp() {
        pendingInvocationRepository = Mockito.mock(PendingInvocationRepository.class);
        conversationRepository = Mockito.mock(ConversationRepository.class);
        messageRepository = Mockito.mock(MessageRepository.class);
        agentRepository = Mockito.mock(AgentRepository.class);
        mapper = new PendingInvocationMapper();
        service = new PendingInvocationService(pendingInvocationRepository, conversationRepository,
                messageRepository, agentRepository, mapper);
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
        when(pendingInvocationRepository.findByConversationId(conversationId))
                .thenReturn(List.of(mapper.toEntity(a), mapper.toEntity(b)));

        List<PendingInvocation> result = service.listPendingInvocationsForConversation(conversationId);

        assertThat(result).extracting(PendingInvocation::id).containsExactly(a.id(), b.id());
    }

    @Test
    void claimNextBatch_marksEachRowProcessingUnderWorkerId() {
        PendingInvocation a = PendingInvocation.create(tenantId, conversationId, UUID.randomUUID());
        PendingInvocation b = PendingInvocation.create(tenantId, conversationId, UUID.randomUUID());
        when(pendingInvocationRepository.claimNextBatch(5))
                .thenReturn(List.of(mapper.toEntity(a), mapper.toEntity(b)));

        List<PendingInvocation> claimed = service.claimNextBatch("worker-x", 5);

        assertThat(claimed).hasSize(2);
        assertThat(claimed).allSatisfy(i -> {
            assertThat(i.status()).isEqualTo(PendingInvocationStatus.PROCESSING);
            assertThat(i.claimedBy()).isEqualTo("worker-x");
            assertThat(i.attemptCount()).isEqualTo(1);
        });
        verify(pendingInvocationRepository, times(2)).save(any(PendingInvocationEntity.class));
    }

    @Test
    void claimNextBatch_whenNoneAvailable_returnsEmpty() {
        when(pendingInvocationRepository.claimNextBatch(5)).thenReturn(List.of());

        List<PendingInvocation> claimed = service.claimNextBatch("worker-x", 5);

        assertThat(claimed).isEmpty();
        verify(pendingInvocationRepository, Mockito.never()).save(any(PendingInvocationEntity.class));
    }

    @Test
    void markCompleted_savesCompletedTransition() {
        PendingInvocation processing = processingInvocation();
        when(pendingInvocationRepository.findById(processing.id()))
                .thenReturn(Optional.of(mapper.toEntity(processing)));

        service.markCompleted(processing.id());

        ArgumentCaptor<PendingInvocationEntity> captor =
                ArgumentCaptor.forClass(PendingInvocationEntity.class);
        verify(pendingInvocationRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(PendingInvocationStatus.COMPLETED);
        assertThat(captor.getValue().getCompletedAt()).isNotNull();
    }

    @Test
    void markFailed_savesFailedTransitionWithError() {
        PendingInvocation processing = processingInvocation();
        when(pendingInvocationRepository.findById(processing.id()))
                .thenReturn(Optional.of(mapper.toEntity(processing)));

        service.markFailed(processing.id(), "401 unauthorized");

        ArgumentCaptor<PendingInvocationEntity> captor =
                ArgumentCaptor.forClass(PendingInvocationEntity.class);
        verify(pendingInvocationRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(PendingInvocationStatus.FAILED);
        assertThat(captor.getValue().getLastError()).isEqualTo("401 unauthorized");
    }

    @Test
    void markAbandoned_savesAbandonedTransitionWithError() {
        PendingInvocation processing = processingInvocation();
        when(pendingInvocationRepository.findById(processing.id()))
                .thenReturn(Optional.of(mapper.toEntity(processing)));

        service.markAbandoned(processing.id(), "exhausted retries");

        ArgumentCaptor<PendingInvocationEntity> captor =
                ArgumentCaptor.forClass(PendingInvocationEntity.class);
        verify(pendingInvocationRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(PendingInvocationStatus.ABANDONED);
        assertThat(captor.getValue().getLastError()).isEqualTo("exhausted retries");
    }

    @Test
    void releaseForRetry_savesPendingTransitionWithBackoff() {
        PendingInvocation processing = processingInvocation();
        when(pendingInvocationRepository.findById(processing.id()))
                .thenReturn(Optional.of(mapper.toEntity(processing)));

        service.releaseForRetry(processing.id(), "429 throttled", 30L);

        ArgumentCaptor<PendingInvocationEntity> captor =
                ArgumentCaptor.forClass(PendingInvocationEntity.class);
        verify(pendingInvocationRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(PendingInvocationStatus.PENDING);
        assertThat(captor.getValue().getNextAttemptAt()).isNotNull();
        assertThat(captor.getValue().getClaimedAt()).isNull();
        assertThat(captor.getValue().getClaimedBy()).isNull();
    }

    @Test
    void markCompleted_whenNotFound_throwsPendingInvocationNotFound() {
        UUID id = UUID.randomUUID();
        when(pendingInvocationRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.markCompleted(id))
                .isInstanceOf(PendingInvocationNotFoundException.class);
    }

    @Test
    void findOrphanedSince_returnsMappedRows() {
        Instant threshold = Instant.now().minusSeconds(600);
        PendingInvocation processing = processingInvocation();
        when(pendingInvocationRepository.findOrphanedSince(eq(threshold)))
                .thenReturn(List.of(mapper.toEntity(processing)));

        List<PendingInvocation> orphans = service.findOrphanedSince(threshold);

        assertThat(orphans).hasSize(1);
        assertThat(orphans.get(0).id()).isEqualTo(processing.id());
        assertThat(orphans.get(0).status()).isEqualTo(PendingInvocationStatus.PROCESSING);
    }

    private PendingInvocation processingInvocation() {
        PendingInvocation pending = PendingInvocation.create(tenantId, conversationId, UUID.randomUUID());
        return pending.claim("worker-x");
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
