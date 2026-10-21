package dev.cauce.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.cauce.core.agent.Agent;
import dev.cauce.core.agent.AgentNotFoundException;
import dev.cauce.core.agent.AgentStatus;
import dev.cauce.core.agent.UnknownToolException;
import dev.cauce.core.audit.AuditContentHash;
import dev.cauce.core.audit.AuditEvent;
import dev.cauce.core.audit.AuditEventRecorder;
import dev.cauce.core.tenant.InvalidTenantTierException;
import dev.cauce.core.tenant.TenantContext;
import dev.cauce.core.tenant.TenantNotFoundException;
import dev.cauce.core.tenant.Tier;
import dev.cauce.core.tool.ToolCatalog;
import dev.cauce.memory.agent.AgentEntity;
import dev.cauce.memory.agent.AgentMapper;
import dev.cauce.memory.agent.AgentRepository;
import dev.cauce.memory.tenant.TenantEntity;
import dev.cauce.memory.tenant.TenantRepository;
import dev.cauce.tenancy.audit.AdminAuditEvents;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

class AgentServiceTest {

    private final UUID actorTenantId = UUID.randomUUID();

    private AgentRepository agentRepository;
    private TenantRepository tenantRepository;
    private AuditEventRecorder auditRecorder;
    private AgentService service;

    @BeforeEach
    void setUp() {
        agentRepository = Mockito.mock(AgentRepository.class);
        tenantRepository = Mockito.mock(TenantRepository.class);
        auditRecorder = Mockito.mock(AuditEventRecorder.class);
        service = new AgentService(agentRepository, tenantRepository, new AgentMapper(),
                auditRecorder, catalogWith("get_current_time"));
        when(agentRepository.save(any(AgentEntity.class))).thenAnswer(call -> call.getArgument(0));
        TenantContext.setCurrentTenantId(actorTenantId);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void createAgent_whenTenantNotFound_throwsTenantNotFound() {
        UUID tenantId = UUID.randomUUID();
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createAgent(tenantId, "Bot", "p", "anthropic", "m"))
                .isInstanceOf(TenantNotFoundException.class);
    }

    @Test
    void createAgent_whenTenantIsOperator_throwsInvalidTenantTier() {
        UUID tenantId = UUID.randomUUID();
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant(tenantId, Tier.OPERATOR)));

        assertThatThrownBy(() -> service.createAgent(tenantId, "Bot", "p", "anthropic", "m"))
                .isInstanceOf(InvalidTenantTierException.class);
    }

    @Test
    void createAgent_whenTenantIsPartner_throwsInvalidTenantTier() {
        UUID tenantId = UUID.randomUUID();
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant(tenantId, Tier.PARTNER)));

        assertThatThrownBy(() -> service.createAgent(tenantId, "Bot", "p", "anthropic", "m"))
                .isInstanceOf(InvalidTenantTierException.class);
    }

    @Test
    void createAgent_whenProviderUnsupported_throws() {
        UUID tenantId = UUID.randomUUID();
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant(tenantId, Tier.CLIENT)));

        assertThatThrownBy(() -> service.createAgent(tenantId, "Bot", "p", "no-such-provider", "m"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(auditRecorder); // no fact, no audit record
    }

    @Test
    void createAgent_recordsAdminAuditWithPromptHashNeverRawPrompt() {
        UUID tenantId = UUID.randomUUID();
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant(tenantId, Tier.CLIENT)));

        Agent agent = service.createAgent(tenantId, "DentalBot", "You are a secret strategy.",
                "anthropic", "claude-sonnet-4-7");

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditRecorder).record(captor.capture());
        AuditEvent event = captor.getValue();
        assertThat(event.tenantId()).isEqualTo(tenantId); // the owning tenant's chain
        assertThat(event.eventType()).isEqualTo(AdminAuditEvents.AGENT_CREATED);
        assertThat(event.payload())
                .containsEntry("agent_id", agent.id().toString())
                .containsEntry("tenant_id", tenantId.toString())
                .containsEntry("model_provider", "anthropic")
                .containsEntry("model_name", "claude-sonnet-4-7")
                .containsEntry("prompt_hash",
                        AuditContentHash.of(tenantId, "You are a secret strategy."))
                .containsEntry("prompt_length", 26)
                .containsEntry("actor_tenant_id", actorTenantId.toString());
        // The prompt (sensitive business logic) enters ONLY as a hash.
        assertThat(event.payload().values()).doesNotContain("You are a secret strategy.");
    }

    @Test
    void createAgent_whenClientTenantValid_persistsAndReturnsAgent() {
        UUID tenantId = UUID.randomUUID();
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant(tenantId, Tier.CLIENT)));

        Agent agent = service.createAgent(tenantId, "DentalBot", "You are helpful.",
                "anthropic", "claude-sonnet-4-7");

        assertThat(agent.tenantId()).isEqualTo(tenantId);
        assertThat(agent.name()).isEqualTo("DentalBot");
        assertThat(agent.modelProvider()).isEqualTo("anthropic");
        assertThat(agent.status()).isEqualTo(AgentStatus.DRAFT);
    }

    @Test
    void createAgent_withoutLlmConfig_persistsWithDefaults() {
        UUID tenantId = UUID.randomUUID();
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant(tenantId, Tier.CLIENT)));

        Agent agent = service.createAgent(tenantId, "Bot", "p", "anthropic", "claude-sonnet-4-7");

        assertThat(agent.temperature()).isEmpty();
        assertThat(agent.maxResponseTokens()).isEqualTo(Agent.DEFAULT_MAX_RESPONSE_TOKENS);
    }

    @Test
    void createAgent_withExplicitLlmConfig_persistsThoseValues() {
        UUID tenantId = UUID.randomUUID();
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant(tenantId, Tier.CLIENT)));

        Agent agent = service.createAgent(tenantId, "Bot", "p", "anthropic", "claude-sonnet-4-7",
                0.2, 12000);

        assertThat(agent.temperature()).contains(0.2);
        assertThat(agent.maxResponseTokens()).isEqualTo(12000);
    }

    @Test
    void createAgent_whenTemperatureOutOfRange_throws() {
        UUID tenantId = UUID.randomUUID();
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant(tenantId, Tier.CLIENT)));

        assertThatThrownBy(() ->
                service.createAgent(tenantId, "Bot", "p", "anthropic", "claude-sonnet-4-7", 1.5, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static TenantEntity tenant(UUID id, Tier tier) {
        Instant now = Instant.now();
        return new TenantEntity(id, null, tier, "name", now, now);
    }

    // --- tools per agent (ADR 0005, C4) ---

    @Test
    void createAgent_grantsNoToolByDefault() {
        UUID tenantId = stubClientTenant();

        Agent agent = service.createAgent(tenantId, "Bot", "p", "anthropic", "m");

        assertThat(agent.toolNames()).isEmpty();
    }

    @Test
    void createAgent_withARegisteredTool_grantsIt() {
        UUID tenantId = stubClientTenant();

        Agent agent = service.createAgent(tenantId, "Bot", "p", "anthropic", "m", null, null, null,
                List.of("get_current_time"));

        assertThat(agent.toolNames()).containsExactly("get_current_time");
    }

    @Test
    void createAgent_withAnUnregisteredTool_isRejectedAndPersistsNothing() {
        UUID tenantId = stubClientTenant();

        assertThatThrownBy(() -> service.createAgent(tenantId, "Bot", "p", "anthropic", "m", null, null, null,
                List.of("get_current_time", "send_email")))
                .isInstanceOf(UnknownToolException.class)
                .hasMessageContaining("send_email");
        verify(agentRepository, never()).save(any());
    }

    @Test
    void setTools_replacesTheGrant_andAuditsItAsAnAgentUpdate() {
        Agent existing = Agent.create(UUID.randomUUID(), "Bot", "p", "anthropic", "m");
        when(agentRepository.findById(existing.id()))
                .thenReturn(Optional.of(new AgentMapper().toEntity(existing)));

        Agent updated = service.setTools(existing.id(), List.of("get_current_time"));

        assertThat(updated.toolNames()).containsExactly("get_current_time");
        ArgumentCaptor<AuditEvent> audit = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditRecorder).record(audit.capture());
        assertThat(audit.getValue().eventType()).isEqualTo("admin.agent.updated");
        assertThat(audit.getValue().tenantId()).isEqualTo(existing.tenantId());
        assertThat(audit.getValue().payload())
                .containsEntry("change", "tools")
                .containsEntry("tool_names", List.of("get_current_time"))
                .containsEntry("actor_tenant_id", actorTenantId.toString());
    }

    @Test
    void setTools_withAnUnregisteredTool_isRejected_andNothingIsSavedOrAudited() {
        Agent existing = Agent.create(UUID.randomUUID(), "Bot", "p", "anthropic", "m");
        when(agentRepository.findById(existing.id()))
                .thenReturn(Optional.of(new AgentMapper().toEntity(existing)));

        assertThatThrownBy(() -> service.setTools(existing.id(), List.of("send_email")))
                .isInstanceOf(UnknownToolException.class);
        verify(agentRepository, never()).save(any());
        verify(auditRecorder, never()).record(any());
    }

    @Test
    void updateAgent_editsThePresentFields_andAuditsThePromptByHash() {
        Agent existing = Agent.create(UUID.randomUUID(), "Bot", "Old prompt.", "anthropic", "m")
                .withTools(List.of("get_current_time"));
        when(agentRepository.findById(existing.id()))
                .thenReturn(Optional.of(new AgentMapper().toEntity(existing)));

        Agent updated = service.updateAgent(existing.id(), null, "New prompt.", null);

        assertThat(updated.name()).isEqualTo("Bot");
        assertThat(updated.systemPrompt()).isEqualTo("New prompt.");
        assertThat(updated.toolNames()).containsExactly("get_current_time");
        ArgumentCaptor<AuditEvent> audit = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditRecorder).record(audit.capture());
        assertThat(audit.getValue().eventType()).isEqualTo("admin.agent.updated");
        assertThat(audit.getValue().payload())
                .containsEntry("change", "configuration")
                .containsEntry("prompt_length", "New prompt.".length())
                .doesNotContainValue("New prompt.");
    }

    @Test
    void updateAgent_whenTheAgentIsNotVisible_throwsAgentNotFound() {
        UUID agentId = UUID.randomUUID();
        when(agentRepository.findById(agentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateAgent(agentId, "Bar", null, null))
                .isInstanceOf(AgentNotFoundException.class);
        verify(auditRecorder, never()).record(any());
    }

    @Test
    void setTools_whenTheAgentIsNotVisible_throwsAgentNotFound() {
        UUID agentId = UUID.randomUUID();
        when(agentRepository.findById(agentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.setTools(agentId, List.of()))
                .isInstanceOf(AgentNotFoundException.class);
    }

    @Test
    void withoutAToolCatalog_anyGrantIsRejected_butAnEmptyOneWorks() {
        AgentService noCatalog = new AgentService(agentRepository, tenantRepository, new AgentMapper(),
                auditRecorder, catalogWith());
        UUID tenantId = stubClientTenant();

        assertThat(noCatalog.createAgent(tenantId, "Bot", "p", "anthropic", "m").toolNames()).isEmpty();
        assertThatThrownBy(() -> noCatalog.createAgent(tenantId, "Bot", "p", "anthropic", "m", null, null, null,
                List.of("get_current_time")))
                .isInstanceOf(UnknownToolException.class);
    }

    private UUID stubClientTenant() {
        UUID tenantId = UUID.randomUUID();
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant(tenantId, Tier.CLIENT)));
        return tenantId;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<ToolCatalog> catalogWith(String... names) {
        ObjectProvider<ToolCatalog> provider = Mockito.mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(names.length == 0 ? null : Set.of(names)::contains);
        return provider;
    }
}
