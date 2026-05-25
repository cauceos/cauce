package dev.cauce.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import dev.cauce.core.agent.AgentNotFoundException;
import dev.cauce.core.agent.AgentStatus;
import dev.cauce.core.conversation.Conversation;
import dev.cauce.core.conversation.ConversationNotFoundException;
import dev.cauce.core.conversation.ConversationStatus;
import dev.cauce.core.conversation.InvalidChannelTypeException;
import dev.cauce.core.conversation.InvalidConversationTransitionException;
import dev.cauce.memory.agent.AgentEntity;
import dev.cauce.memory.agent.AgentRepository;
import dev.cauce.memory.conversation.ConversationEntity;
import dev.cauce.memory.conversation.ConversationMapper;
import dev.cauce.memory.conversation.ConversationRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ConversationServiceTest {

    private ConversationRepository conversationRepository;
    private AgentRepository agentRepository;
    private ConversationService service;

    @BeforeEach
    void setUp() {
        conversationRepository = Mockito.mock(ConversationRepository.class);
        agentRepository = Mockito.mock(AgentRepository.class);
        service = new ConversationService(conversationRepository, agentRepository, new ConversationMapper());
        when(conversationRepository.save(any(ConversationEntity.class)))
                .thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void startConversation_whenAgentNotFound_throwsAgentNotFound() {
        UUID agentId = UUID.randomUUID();
        when(agentRepository.findById(agentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.startConversation(agentId, "whatsapp", "+34612345678"))
                .isInstanceOf(AgentNotFoundException.class);
    }

    @Test
    void startConversation_whenChannelTypeUnsupported_throwsInvalidChannelType() {
        UUID agentId = UUID.randomUUID();
        when(agentRepository.findById(agentId)).thenReturn(Optional.of(agent(agentId)));

        assertThatThrownBy(() -> service.startConversation(agentId, "telegram", "+34612345678"))
                .isInstanceOf(InvalidChannelTypeException.class);
    }

    @Test
    void startConversation_whenValid_persistsAndReturnsConversation() {
        UUID agentId = UUID.randomUUID();
        when(agentRepository.findById(agentId)).thenReturn(Optional.of(agent(agentId)));

        Conversation conversation = service.startConversation(agentId, "whatsapp", "+34612345678");

        assertThat(conversation.agentId()).isEqualTo(agentId);
        assertThat(conversation.channelType()).isEqualTo("whatsapp");
        assertThat(conversation.externalIdentityRef()).isEqualTo("+34612345678");
        assertThat(conversation.status()).isEqualTo(ConversationStatus.OPEN);
        assertThat(conversation.closedAt()).isNull();
    }

    @Test
    void findActiveConversation_whenNoOpenConversation_returnsEmpty() {
        UUID agentId = UUID.randomUUID();
        when(conversationRepository.findByAgentIdAndChannelTypeAndExternalIdentityRefAndStatus(
                agentId, "whatsapp", "+34612345678", ConversationStatus.OPEN))
                .thenReturn(Optional.empty());

        assertThat(service.findActiveConversation(agentId, "whatsapp", "+34612345678")).isEmpty();
    }

    @Test
    void findActiveConversation_whenOpenConversationExists_returnsIt() {
        UUID agentId = UUID.randomUUID();
        Conversation open = Conversation.start(agentId, "whatsapp", "+34612345678");
        when(conversationRepository.findByAgentIdAndChannelTypeAndExternalIdentityRefAndStatus(
                eq(agentId), eq("whatsapp"), eq("+34612345678"), eq(ConversationStatus.OPEN)))
                .thenReturn(Optional.of(new ConversationMapper().toEntity(open)));

        Optional<Conversation> found = service.findActiveConversation(agentId, "whatsapp", "+34612345678");

        assertThat(found).isPresent();
        assertThat(found.get().id()).isEqualTo(open.id());
        assertThat(found.get().status()).isEqualTo(ConversationStatus.OPEN);
    }

    // --- lifecycle transitions ---

    @Test
    void closeConversation_whenOpen_persistsClosedConversation() {
        UUID id = UUID.randomUUID();
        when(conversationRepository.findById(id))
                .thenReturn(Optional.of(conversationEntity(id, ConversationStatus.OPEN)));

        Conversation closed = service.closeConversation(id);

        assertThat(closed.status()).isEqualTo(ConversationStatus.CLOSED);
        assertThat(closed.closedAt()).isNotNull();
    }

    @Test
    void closeConversation_whenNotFound_throwsConversationNotFound() {
        UUID id = UUID.randomUUID();
        when(conversationRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.closeConversation(id))
                .isInstanceOf(ConversationNotFoundException.class);
    }

    @Test
    void closeConversation_whenAlreadyClosed_propagatesInvalidTransition() {
        UUID id = UUID.randomUUID();
        when(conversationRepository.findById(id))
                .thenReturn(Optional.of(conversationEntity(id, ConversationStatus.CLOSED)));

        assertThatThrownBy(() -> service.closeConversation(id))
                .isInstanceOf(InvalidConversationTransitionException.class);
    }

    @Test
    void escalateConversation_whenOpen_persistsEscalatedConversation() {
        UUID id = UUID.randomUUID();
        when(conversationRepository.findById(id))
                .thenReturn(Optional.of(conversationEntity(id, ConversationStatus.OPEN)));

        Conversation escalated = service.escalateConversation(id);

        assertThat(escalated.status()).isEqualTo(ConversationStatus.ESCALATED);
        assertThat(escalated.escalatedAt()).isNotNull();
    }

    @Test
    void escalateConversation_whenNotFound_throwsConversationNotFound() {
        UUID id = UUID.randomUUID();
        when(conversationRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.escalateConversation(id))
                .isInstanceOf(ConversationNotFoundException.class);
    }

    @Test
    void escalateConversation_whenAlreadyEscalated_propagatesInvalidTransition() {
        UUID id = UUID.randomUUID();
        when(conversationRepository.findById(id))
                .thenReturn(Optional.of(conversationEntity(id, ConversationStatus.ESCALATED)));

        assertThatThrownBy(() -> service.escalateConversation(id))
                .isInstanceOf(InvalidConversationTransitionException.class);
    }

    @Test
    void archiveConversation_whenOpen_persistsArchivedConversation() {
        UUID id = UUID.randomUUID();
        when(conversationRepository.findById(id))
                .thenReturn(Optional.of(conversationEntity(id, ConversationStatus.OPEN)));

        Conversation archived = service.archiveConversation(id);

        assertThat(archived.status()).isEqualTo(ConversationStatus.ARCHIVED);
        assertThat(archived.archivedAt()).isNotNull();
    }

    @Test
    void archiveConversation_whenNotFound_throwsConversationNotFound() {
        UUID id = UUID.randomUUID();
        when(conversationRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.archiveConversation(id))
                .isInstanceOf(ConversationNotFoundException.class);
    }

    @Test
    void archiveConversation_whenAlreadyArchived_propagatesInvalidTransition() {
        UUID id = UUID.randomUUID();
        when(conversationRepository.findById(id))
                .thenReturn(Optional.of(conversationEntity(id, ConversationStatus.ARCHIVED)));

        assertThatThrownBy(() -> service.archiveConversation(id))
                .isInstanceOf(InvalidConversationTransitionException.class);
    }

    private static AgentEntity agent(UUID id) {
        Instant now = Instant.now();
        return new AgentEntity(id, UUID.randomUUID(), "DentalBot", "prompt",
                "anthropic", "claude-sonnet-4-7", 0.7, 4096, AgentStatus.ACTIVE, now, now);
    }

    private static ConversationEntity conversationEntity(UUID id, ConversationStatus status) {
        Instant now = Instant.now();
        return new ConversationEntity(id, UUID.randomUUID(), "whatsapp", "+34612345678",
                status, now, now,
                status == ConversationStatus.CLOSED ? now : null,
                status == ConversationStatus.ESCALATED ? now : null,
                status == ConversationStatus.ARCHIVED ? now : null);
    }
}
