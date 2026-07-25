package dev.cauce.channels;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.cauce.channels.audit.ChannelAuditEvents;
import dev.cauce.channels.persistence.ChannelConfigEntity;
import dev.cauce.channels.persistence.ChannelConfigMapper;
import dev.cauce.channels.persistence.ChannelConfigRepository;
import dev.cauce.channels.spi.ChannelAdapterRegistry;
import dev.cauce.channels.spi.InboundChannelAdapter;
import dev.cauce.core.agent.AgentNotFoundException;
import dev.cauce.core.apikey.ApiKeyHasher;
import dev.cauce.core.audit.AuditEvent;
import dev.cauce.core.audit.AuditEventRecorder;
import dev.cauce.core.conversation.InvalidChannelTypeException;
import dev.cauce.core.tenant.TenantContext;
import dev.cauce.memory.agent.AgentEntity;
import dev.cauce.memory.agent.AgentRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ChannelConfigServiceTest {

    private static final ApiKeyHasher FAKE_HASHER = new ApiKeyHasher() {
        @Override
        public String hash(String plaintext) {
            return "hash:" + plaintext;
        }

        @Override
        public boolean matches(String plaintext, String hash) {
            return hash(plaintext).equals(hash);
        }
    };

    private ChannelConfigRepository repository;
    private AgentRepository agentRepository;
    private AuditEventRecorder auditRecorder;
    private ChannelConfigService service;

    private UUID agentId;
    private UUID tenantId;
    private UUID actorTenantId;

    @BeforeEach
    void setUp() {
        repository = mock(ChannelConfigRepository.class);
        agentRepository = mock(AgentRepository.class);
        auditRecorder = mock(AuditEventRecorder.class);
        InboundChannelAdapter telegram = mock(InboundChannelAdapter.class);
        when(telegram.channelType()).thenReturn("telegram");
        service = new ChannelConfigService(repository, new ChannelConfigMapper(),
                agentRepository, new ChannelAdapterRegistry(List.of(telegram), List.of()),
                FAKE_HASHER, auditRecorder);
        agentId = UUID.randomUUID();
        tenantId = UUID.randomUUID();
        actorTenantId = UUID.randomUUID();
        when(repository.save(any(ChannelConfigEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        TenantContext.setCurrentTenantId(actorTenantId);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void createChannelConfig_validRequest_bindsToTheAgentTenantAndStoresOnlyTheHash() {
        AgentEntity agent = agentOwnedBy(tenantId);
        when(agentRepository.findById(agentId)).thenReturn(Optional.of(agent));

        ChannelConfigCreationResult result =
                service.createChannelConfig(agentId, "telegram", "12345:bot-token");

        assertThat(result.config().tenantId()).isEqualTo(tenantId);
        assertThat(result.config().agentId()).isEqualTo(agentId);
        assertThat(result.config().channelType()).isEqualTo("telegram");
        assertThat(result.webhookSecret()).isNotBlank();
        assertThat(result.config().webhookSecretHash())
                .isEqualTo(FAKE_HASHER.hash(result.webhookSecret()))
                .isNotEqualTo(result.webhookSecret());
    }

    @Test
    void createChannelConfig_recordsAdminAuditWithoutCredentialOrSecret() {
        AgentEntity agent = agentOwnedBy(tenantId);
        when(agentRepository.findById(agentId)).thenReturn(Optional.of(agent));

        ChannelConfigCreationResult result =
                service.createChannelConfig(agentId, "telegram", "12345:bot-token");

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditRecorder).record(captor.capture());
        AuditEvent event = captor.getValue();
        assertThat(event.tenantId()).isEqualTo(tenantId); // the owning tenant's chain
        assertThat(event.eventType()).isEqualTo(ChannelAuditEvents.CHANNEL_CONFIGURED);
        assertThat(event.payload())
                .containsEntry("channel_config_id", result.config().id().toString())
                .containsEntry("agent_id", agentId.toString())
                .containsEntry("tenant_id", tenantId.toString())
                .containsEntry("channel_type", "telegram")
                .containsEntry("actor_tenant_id", actorTenantId.toString());
        // Provider credential, webhook secret, and its hash never reach the sink.
        assertThat(event.payload().values()).doesNotContain("12345:bot-token",
                result.webhookSecret(), result.config().webhookSecretHash());
    }

    @Test
    void createChannelConfig_agentNotVisible_throwsAgentNotFound() {
        when(agentRepository.findById(agentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createChannelConfig(agentId, "telegram", "token"))
                .isInstanceOf(AgentNotFoundException.class);
        verifyNoInteractions(repository, auditRecorder);
    }

    @Test
    void createChannelConfig_channelWithoutAdapter_throwsInvalidChannelType() {
        AgentEntity agent = agentOwnedBy(tenantId);
        when(agentRepository.findById(agentId)).thenReturn(Optional.of(agent));

        assertThatThrownBy(() -> service.createChannelConfig(agentId, "whatsapp", "token"))
                .isInstanceOf(InvalidChannelTypeException.class)
                .hasMessageContaining("whatsapp");
        verifyNoInteractions(repository, auditRecorder);
    }

    private AgentEntity agentOwnedBy(UUID owningTenantId) {
        AgentEntity agent = mock(AgentEntity.class);
        when(agent.getTenantId()).thenReturn(owningTenantId);
        return agent;
    }
}
