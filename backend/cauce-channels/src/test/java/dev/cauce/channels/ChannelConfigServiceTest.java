package dev.cauce.channels;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.cauce.channels.audit.ChannelAuditEvents;
import dev.cauce.channels.config.ChannelConfig;
import dev.cauce.channels.config.ChannelConfigStatus;
import dev.cauce.channels.config.ChannelConfigNotFoundException;
import dev.cauce.channels.persistence.ChannelConfigEntity;
import dev.cauce.channels.persistence.ChannelConfigMapper;
import dev.cauce.channels.persistence.ChannelConfigRepository;
import dev.cauce.channels.secret.AesGcmSecretCipher;
import dev.cauce.channels.secret.SecretEncryptionProperties;
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

    private dev.cauce.orchestration.usage.SpendLimitService spendLimits;
    private UUID agentId;
    private UUID tenantId;
    private UUID actorTenantId;

    @BeforeEach
    void setUp() {
        repository = mock(ChannelConfigRepository.class);
        agentRepository = mock(AgentRepository.class);
        auditRecorder = mock(AuditEventRecorder.class);
        spendLimits = mock(dev.cauce.orchestration.usage.SpendLimitService.class);
        InboundChannelAdapter telegram = mock(InboundChannelAdapter.class);
        when(telegram.channelType()).thenReturn("telegram");
        service = new ChannelConfigService(repository, new ChannelConfigMapper(new AesGcmSecretCipher(SecretEncryptionProperties.DEV_ONLY_KEY)),
                agentRepository, new ChannelAdapterRegistry(List.of(telegram), List.of()),
                FAKE_HASHER, auditRecorder, spendLimits);
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
    void disable_switchesTheConfigOff_keepingItsConfiguration_auditsOnce_andIsIdempotent() {
        ChannelConfig telegram = ChannelConfig.create(tenantId, agentId, "telegram", "token", "hash");
        ChannelConfigMapper mapper = new ChannelConfigMapper(new AesGcmSecretCipher(SecretEncryptionProperties.DEV_ONLY_KEY));
        when(repository.findById(telegram.id())).thenReturn(Optional.of(mapper.toEntity(telegram)),
                Optional.of(mapper.toEntity(telegram.withStatus(ChannelConfigStatus.DISABLED))));

        ChannelConfig disabled = service.disable(telegram.id());
        service.disable(telegram.id());

        assertThat(disabled.status()).isEqualTo(ChannelConfigStatus.DISABLED);
        assertThat(disabled.credential()).isEqualTo("token");
        assertThat(disabled.webhookSecretHash()).isEqualTo("hash");
        ArgumentCaptor<AuditEvent> audit = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditRecorder).record(audit.capture());
        assertThat(audit.getValue().eventType()).isEqualTo("admin.channel.disabled");
        assertThat(audit.getValue().payload()).containsEntry("actor_tenant_id", actorTenantId.toString())
                .doesNotContainKey("credential");
    }

    @Test
    void enable_aWebChatWithoutASpendLimit_isRefused_andWithOne_switchesItOn() {
        ChannelConfigMapper mapper = new ChannelConfigMapper(new AesGcmSecretCipher(SecretEncryptionProperties.DEV_ONLY_KEY));
        ChannelConfig web = ChannelConfig.createWebChat(tenantId, agentId, "pk_ABCDEFGHIJKLMNOPQRSTUVWX", "secreto")
                .withStatus(ChannelConfigStatus.DISABLED);
        when(repository.findById(web.id())).thenReturn(Optional.of(mapper.toEntity(web)));
        when(spendLimits.status(tenantId)).thenReturn(spendStatus(null), spendStatus(new java.math.BigDecimal("50.00")));

        assertThatThrownBy(() -> service.enable(web.id()))
                .isInstanceOf(dev.cauce.core.usage.SpendLimitRequiredException.class);
        assertThat(service.enable(web.id()).status()).isEqualTo(ChannelConfigStatus.ACTIVE);
    }

    private dev.cauce.orchestration.usage.SpendStatus spendStatus(java.math.BigDecimal limit) {
        return new dev.cauce.orchestration.usage.SpendStatus(tenantId, limit, java.time.YearMonth.of(2026, 11),
                java.math.BigDecimal.ZERO, 0, limit == null ? dev.cauce.orchestration.usage.SpendLimit.SpendState.NONE
                : dev.cauce.orchestration.usage.SpendLimit.SpendState.OK);
    }

    @Test
    void rotateWebhookSecret_storesOnlyTheNewHash_andAuditsWithoutTheSecret() {
        ChannelConfig existing = ChannelConfig.create(tenantId, agentId, "telegram", "12345:token", "hash:old");
        ChannelConfigMapper mapper = new ChannelConfigMapper(new AesGcmSecretCipher(SecretEncryptionProperties.DEV_ONLY_KEY));
        when(repository.findById(existing.id())).thenReturn(Optional.of(mapper.toEntity(existing)));

        ChannelConfigCreationResult rotated = service.rotateWebhookSecret(existing.id());

        assertThat(rotated.webhookSecret()).isNotBlank();
        assertThat(rotated.config().id()).isEqualTo(existing.id());
        assertThat(rotated.config().webhookSecretHash()).isEqualTo("hash:" + rotated.webhookSecret());
        ArgumentCaptor<AuditEvent> audit = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditRecorder).record(audit.capture());
        assertThat(audit.getValue().eventType()).isEqualTo("admin.channel.webhook_secret_rotated");
        assertThat(audit.getValue().payload().values()).doesNotContain(rotated.webhookSecret(),
                rotated.config().webhookSecretHash());
    }

    @Test
    void rotateWebhookSecret_ofAnInvisibleConfig_isNotFound_andOfAWhatsAppNumberIsRefused() {
        UUID missing = UUID.randomUUID();
        when(repository.findById(missing)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.rotateWebhookSecret(missing))
                .isInstanceOf(ChannelConfigNotFoundException.class);

        ChannelConfig number = ChannelConfig.createWhatsAppNumber(tenantId, agentId, UUID.randomUUID(), "111",
                "EAAG-token");
        ChannelConfigMapper mapper = new ChannelConfigMapper(new AesGcmSecretCipher(SecretEncryptionProperties.DEV_ONLY_KEY));
        when(repository.findById(number.id())).thenReturn(Optional.of(mapper.toEntity(number)));
        assertThatThrownBy(() -> service.rotateWebhookSecret(number.id()))
                .isInstanceOf(IllegalArgumentException.class);
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
