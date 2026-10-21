package dev.cauce.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.cauce.core.agent.AgentNotFoundException;
import dev.cauce.core.agent.AgentStatus;
import dev.cauce.core.conversation.Conversation;
import dev.cauce.core.conversation.ConversationNotFoundException;
import dev.cauce.core.conversation.ConversationStatus;
import dev.cauce.core.conversation.InvalidChannelTypeException;
import dev.cauce.core.conversation.InvalidConversationTransitionException;
import dev.cauce.core.identity.Identity;
import dev.cauce.core.identity.IdentityKind;
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

    private static final UUID CONFIG = UUID.randomUUID();

    private ConversationRepository conversationRepository;
    private AgentRepository agentRepository;
    private IdentityService identityService;
    private ConversationService service;

    @BeforeEach
    void setUp() {
        conversationRepository = Mockito.mock(ConversationRepository.class);
        agentRepository = Mockito.mock(AgentRepository.class);
        identityService = Mockito.mock(IdentityService.class);
        service = new ConversationService(conversationRepository, agentRepository, new ConversationMapper(),
                identityService);
        when(conversationRepository.save(any(ConversationEntity.class)))
                .thenAnswer(call -> call.getArgument(0));
        when(identityService.resolveOrCreate(any(UUID.class), any(String.class), any(IdentityKind.class),
                any(String.class)))
                .thenAnswer(call -> Identity.create(call.getArgument(0), call.getArgument(1),
                        call.getArgument(2), call.getArgument(3)));
    }

    @Test
    void startConversation_whenAgentNotFound_throwsAgentNotFound() {
        UUID agentId = UUID.randomUUID();
        when(agentRepository.findById(agentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.startConversation(agentId, "whatsapp", IdentityKind.PHONE_NUMBER,
                "+34612345678", CONFIG))
                .isInstanceOf(AgentNotFoundException.class);
    }

    @Test
    void startConversation_whenChannelTypeUnsupported_throwsInvalidChannelType() {
        UUID agentId = UUID.randomUUID();
        when(agentRepository.findById(agentId)).thenReturn(Optional.of(agent(agentId)));

        // "discord" has no adapter and is not in the supported set ("telegram" is, since the
        // cauce-channels inbound adapter landed).
        assertThatThrownBy(() -> service.startConversation(agentId, "discord", IdentityKind.PROVIDER_USER_ID,
                "42", CONFIG))
                .isInstanceOf(InvalidChannelTypeException.class);
    }

    @Test
    void startConversation_whenValid_resolvesTheIdentityInTheAgentsTenantAndPersists() {
        UUID agentId = UUID.randomUUID();
        AgentEntity agent = agent(agentId);
        when(agentRepository.findById(agentId)).thenReturn(Optional.of(agent));

        Conversation conversation = service.startConversation(agentId, "whatsapp", IdentityKind.PHONE_NUMBER,
                "+34612345678", CONFIG);

        verify(identityService).resolveOrCreate(agent.getTenantId(), "whatsapp", IdentityKind.PHONE_NUMBER,
                "+34612345678");
        assertThat(conversation.agentId()).isEqualTo(agentId);
        assertThat(conversation.channelConfigId()).contains(CONFIG);
        assertThat(conversation.channelType()).isEqualTo("whatsapp");
        assertThat(conversation.externalIdentityRef()).isEqualTo("+34612345678");
        assertThat(conversation.status()).isEqualTo(ConversationStatus.OPEN);
        assertThat(conversation.closedAt()).isNull();
    }

    @Test
    void startConversation_whenApiChannel_persistsOpenConversationWithoutConfig() {
        UUID agentId = UUID.randomUUID();
        when(agentRepository.findById(agentId)).thenReturn(Optional.of(agent(agentId)));

        Conversation conversation = service.startConversation(agentId, "api", IdentityKind.CLIENT_REFERENCE,
                "user-1", null);

        assertThat(conversation.channelType()).isEqualTo("api");
        assertThat(conversation.channelConfigId()).isEmpty();
        assertThat(conversation.status()).isEqualTo(ConversationStatus.OPEN);
    }

    @Test
    void startConversation_whenApiChannelNamesAConfig_isRejected() {
        UUID agentId = UUID.randomUUID();
        when(agentRepository.findById(agentId)).thenReturn(Optional.of(agent(agentId)));

        assertThatThrownBy(() -> service.startConversation(agentId, "api", IdentityKind.CLIENT_REFERENCE,
                "user-1", CONFIG))
                .isInstanceOf(IllegalArgumentException.class);
        verify(identityService, never()).resolveOrCreate(any(), any(), any(), any());
    }

    @Test
    void startConversation_whenAdapterChannelHasNoConfig_isRejected() {
        UUID agentId = UUID.randomUUID();
        when(agentRepository.findById(agentId)).thenReturn(Optional.of(agent(agentId)));

        assertThatThrownBy(() -> service.startConversation(agentId, "telegram", IdentityKind.PROVIDER_USER_ID,
                "42", null))
                .isInstanceOf(IllegalArgumentException.class);
        verify(identityService, never()).resolveOrCreate(any(), any(), any(), any());
    }

    @Test
    void findActiveConversation_whenNoOpenConversation_returnsEmpty() {
        UUID agentId = UUID.randomUUID();
        UUID identityId = UUID.randomUUID();
        when(conversationRepository.findByAgentIdAndIdentityIdAndChannelConfigIdAndStatus(
                agentId, identityId, CONFIG, ConversationStatus.OPEN))
                .thenReturn(Optional.empty());

        assertThat(service.findActiveConversation(agentId, identityId, CONFIG)).isEmpty();
    }

    @Test
    void findActiveConversation_whenOpenConversationExists_returnsIt() {
        UUID agentId = UUID.randomUUID();
        Conversation open = Conversation.start(agentId, identity("whatsapp", IdentityKind.PHONE_NUMBER,
                "+34612345678"), CONFIG);
        when(conversationRepository.findByAgentIdAndIdentityIdAndChannelConfigIdAndStatus(
                agentId, open.identityId(), CONFIG, ConversationStatus.OPEN))
                .thenReturn(Optional.of(new ConversationMapper().toEntity(open)));

        Optional<Conversation> found = service.findActiveConversation(agentId, open.identityId(), CONFIG);

        assertThat(found).isPresent();
        assertThat(found.get().id()).isEqualTo(open.id());
        assertThat(found.get().status()).isEqualTo(ConversationStatus.OPEN);
    }

    // --- resolve-or-create ---

    @Test
    void resolveOrStartConversation_whenActiveExists_returnsItWithoutCreating() {
        UUID agentId = UUID.randomUUID();
        AgentEntity agent = agent(agentId);
        Identity identity = identity("api", IdentityKind.CLIENT_REFERENCE, "user-1");
        stubIdentity(agent, identity);
        Conversation open = Conversation.start(agentId, identity, null);
        when(agentRepository.findById(agentId)).thenReturn(Optional.of(agent));
        when(conversationRepository.findByAgentIdAndIdentityIdAndChannelConfigIdAndStatus(
                agentId, identity.id(), null, ConversationStatus.OPEN))
                .thenReturn(Optional.of(new ConversationMapper().toEntity(open)));

        Conversation resolved = service.resolveOrStartConversation(agentId, "api", IdentityKind.CLIENT_REFERENCE,
                "user-1", null);

        assertThat(resolved.id()).isEqualTo(open.id());
        verify(conversationRepository, never()).insertOpenConversationIfAbsent(any(), any(), any(), any(),
                any(), any());
    }

    @Test
    void resolveOrStartConversation_whenNoActive_insertsAndReturnsTheOpenConversation() {
        UUID agentId = UUID.randomUUID();
        AgentEntity agent = agent(agentId);
        Identity identity = identity("telegram", IdentityKind.PROVIDER_USER_ID, "42");
        stubIdentity(agent, identity);
        ConversationEntity persisted = new ConversationMapper()
                .toEntity(Conversation.start(agentId, identity, CONFIG));
        when(agentRepository.findById(agentId)).thenReturn(Optional.of(agent));
        when(conversationRepository.findByAgentIdAndIdentityIdAndChannelConfigIdAndStatus(
                agentId, identity.id(), CONFIG, ConversationStatus.OPEN))
                .thenReturn(Optional.empty())          // fast path: no open thread
                .thenReturn(Optional.of(persisted));   // re-read after the insert
        when(conversationRepository.insertOpenConversationIfAbsent(any(UUID.class), eq(agentId),
                eq(identity.id()), eq(CONFIG), eq("telegram"), eq("42"))).thenReturn(1);

        Conversation resolved = service.resolveOrStartConversation(agentId, "telegram",
                IdentityKind.PROVIDER_USER_ID, "42", CONFIG);

        assertThat(resolved.identityId()).isEqualTo(identity.id());
        assertThat(resolved.channelConfigId()).contains(CONFIG);
        assertThat(resolved.status()).isEqualTo(ConversationStatus.OPEN);
    }

    @Test
    void resolveOrStartConversation_whenConcurrentInsertWonTheRace_returnsTheWinner() {
        UUID agentId = UUID.randomUUID();
        AgentEntity agent = agent(agentId);
        Identity identity = identity("api", IdentityKind.CLIENT_REFERENCE, "user-1");
        stubIdentity(agent, identity);
        ConversationEntity winner = new ConversationMapper()
                .toEntity(Conversation.start(agentId, identity, null));
        when(agentRepository.findById(agentId)).thenReturn(Optional.of(agent));
        when(conversationRepository.findByAgentIdAndIdentityIdAndChannelConfigIdAndStatus(
                agentId, identity.id(), null, ConversationStatus.OPEN))
                .thenReturn(Optional.empty())        // fast path: none yet
                .thenReturn(Optional.of(winner));    // re-read finds the concurrent winner
        when(conversationRepository.insertOpenConversationIfAbsent(any(UUID.class), eq(agentId),
                eq(identity.id()), eq(null), eq("api"), eq("user-1"))).thenReturn(0);  // lost the race

        Conversation resolved = service.resolveOrStartConversation(agentId, "api", IdentityKind.CLIENT_REFERENCE,
                "user-1", null);

        assertThat(resolved.id()).isEqualTo(winner.getId());
    }

    @Test
    void resolveOrStartConversation_whenAgentNotVisible_throwsAgentNotFoundBeforeResolvingTheIdentity() {
        UUID agentId = UUID.randomUUID();
        when(agentRepository.findById(agentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolveOrStartConversation(agentId, "api", IdentityKind.CLIENT_REFERENCE,
                "user-1", null))
                .isInstanceOf(AgentNotFoundException.class);
        verify(identityService, never()).resolveOrCreate(any(), any(), any(), any());
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
        return new ConversationEntity(id, UUID.randomUUID(), UUID.randomUUID(), CONFIG,
                "whatsapp", "+34612345678", status, now, now,
                status == ConversationStatus.CLOSED ? now : null,
                status == ConversationStatus.ESCALATED ? now : null,
                status == ConversationStatus.ARCHIVED ? now : null);
    }

    private static Identity identity(String channelType, IdentityKind kind, String value) {
        return Identity.create(UUID.randomUUID(), channelType, kind, value);
    }

    private void stubIdentity(AgentEntity agent, Identity identity) {
        when(identityService.resolveOrCreate(agent.getTenantId(), identity.channelType(), identity.kind(),
                identity.value())).thenReturn(identity);
    }
}
