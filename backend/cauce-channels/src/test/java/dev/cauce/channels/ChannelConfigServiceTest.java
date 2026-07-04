package dev.cauce.channels;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.cauce.channels.persistence.ChannelConfigEntity;
import dev.cauce.channels.persistence.ChannelConfigMapper;
import dev.cauce.channels.persistence.ChannelConfigRepository;
import dev.cauce.channels.spi.ChannelAdapterRegistry;
import dev.cauce.channels.spi.InboundChannelAdapter;
import dev.cauce.core.agent.AgentNotFoundException;
import dev.cauce.core.apikey.ApiKeyHasher;
import dev.cauce.core.conversation.InvalidChannelTypeException;
import dev.cauce.memory.agent.AgentEntity;
import dev.cauce.memory.agent.AgentRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
    private ChannelConfigService service;

    private UUID agentId;
    private UUID tenantId;

    @BeforeEach
    void setUp() {
        repository = mock(ChannelConfigRepository.class);
        agentRepository = mock(AgentRepository.class);
        InboundChannelAdapter telegram = mock(InboundChannelAdapter.class);
        when(telegram.channelType()).thenReturn("telegram");
        service = new ChannelConfigService(repository, new ChannelConfigMapper(),
                agentRepository, new ChannelAdapterRegistry(List.of(telegram)), FAKE_HASHER);
        agentId = UUID.randomUUID();
        tenantId = UUID.randomUUID();
        when(repository.save(any(ChannelConfigEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
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
    void createChannelConfig_agentNotVisible_throwsAgentNotFound() {
        when(agentRepository.findById(agentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createChannelConfig(agentId, "telegram", "token"))
                .isInstanceOf(AgentNotFoundException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void createChannelConfig_channelWithoutAdapter_throwsInvalidChannelType() {
        AgentEntity agent = agentOwnedBy(tenantId);
        when(agentRepository.findById(agentId)).thenReturn(Optional.of(agent));

        assertThatThrownBy(() -> service.createChannelConfig(agentId, "whatsapp", "token"))
                .isInstanceOf(InvalidChannelTypeException.class)
                .hasMessageContaining("whatsapp");
        verifyNoInteractions(repository);
    }

    private AgentEntity agentOwnedBy(UUID owningTenantId) {
        AgentEntity agent = mock(AgentEntity.class);
        when(agent.getTenantId()).thenReturn(owningTenantId);
        return agent;
    }
}
