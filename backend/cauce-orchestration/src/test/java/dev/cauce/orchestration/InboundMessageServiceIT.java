package dev.cauce.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.cauce.core.agent.Agent;
import dev.cauce.core.agent.AgentNotFoundException;
import dev.cauce.core.tenant.Tenant;
import dev.cauce.core.tenant.TenantContext;
import dev.cauce.orchestration.support.AbstractOrchestrationIntegrationTest;
import dev.cauce.tenancy.AgentService;
import dev.cauce.tenancy.TenantService;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Integration tests for {@link InboundMessageService} against a real PostgreSQL via
 * Testcontainers, exercising the runtime {@code cauce_app}/RLS path. No worker or LLM provider
 * is wired: this verifies the synchronous ingest unit (resolve-or-create + append USER +
 * enqueue) is atomic, channel-agnostic, and tenant-scoped. Driving the enqueued work end to end
 * is covered by {@code PendingInvocationWorkerIT} (engine) and the cauce-api messaging IT (public).
 */
class InboundMessageServiceIT extends AbstractOrchestrationIntegrationTest {

    @Autowired
    private TenantService tenantService;

    @Autowired
    private AgentService agentService;

    @Autowired
    private InboundMessageService inboundMessageService;

    private JdbcTemplate jdbc;

    private Tenant operator;
    private Tenant partner;
    private Tenant clientA;
    private Tenant clientB;
    private Agent agent; // owned by clientA

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(adminDataSource);
        jdbc.execute("TRUNCATE TABLE api_keys, pending_invocations, messages, conversations, agents, tenants CASCADE");
        TenantContext.clear();

        operator = tenantService.bootstrapOperator("Operator");
        TenantContext.setCurrentTenantId(operator.id());
        partner = tenantService.createPartner("Partner", operator.id());
        TenantContext.setCurrentTenantId(partner.id());
        clientA = tenantService.createClient("Client A", partner.id());
        clientB = tenantService.createClient("Client B", partner.id());
        TenantContext.setCurrentTenantId(clientA.id());
        agent = agentService.createAgent(clientA.id(), "DentalBot",
                "You are a dentist receptionist.", "anthropic", "claude-sonnet-4-7");
        TenantContext.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void ingest_createsConversationUserMessageAndPendingInvocationOwnedByTheAgentTenant() {
        InboundMessageResult result = ingestAs(clientA.id(), agent.id(), "api", "user-1", "Hola");

        assertThat(result.conversationId()).isNotNull();
        assertThat(result.messageId()).isNotNull();
        assertThat(result.invocationId()).isNotNull();

        String channel = jdbc.queryForObject(
                "SELECT channel_type FROM conversations WHERE id = ?", String.class, result.conversationId());
        assertThat(channel).isEqualTo("api");

        Integer userMessages = jdbc.queryForObject(
                "SELECT count(*) FROM messages WHERE conversation_id = ? AND role = 'USER'",
                Integer.class, result.conversationId());
        assertThat(userMessages).isEqualTo(1);

        String status = jdbc.queryForObject(
                "SELECT status FROM pending_invocations WHERE id = ?", String.class, result.invocationId());
        assertThat(status).isEqualTo("PENDING");
        UUID owningTenant = jdbc.queryForObject(
                "SELECT tenant_id FROM pending_invocations WHERE id = ?", UUID.class, result.invocationId());
        assertThat(owningTenant).isEqualTo(clientA.id());
    }

    @Test
    void ingest_secondMessageForSameIdentity_reusesTheOpenConversation() {
        InboundMessageResult first = ingestAs(clientA.id(), agent.id(), "api", "user-1", "Hola");
        InboundMessageResult second = ingestAs(clientA.id(), agent.id(), "api", "user-1", "¿Sigues ahí?");

        assertThat(second.conversationId()).isEqualTo(first.conversationId());

        Integer userMessages = jdbc.queryForObject(
                "SELECT count(*) FROM messages WHERE conversation_id = ? AND role = 'USER'",
                Integer.class, first.conversationId());
        assertThat(userMessages).isEqualTo(2);
        Integer invocations = jdbc.queryForObject(
                "SELECT count(*) FROM pending_invocations WHERE conversation_id = ?",
                Integer.class, first.conversationId());
        assertThat(invocations).isEqualTo(2);
    }

    @Test
    void ingest_differentIdentity_startsADistinctConversation() {
        InboundMessageResult one = ingestAs(clientA.id(), agent.id(), "api", "user-1", "Hola");
        InboundMessageResult two = ingestAs(clientA.id(), agent.id(), "api", "user-2", "Hello");

        assertThat(two.conversationId()).isNotEqualTo(one.conversationId());
        Integer apiConversations = jdbc.queryForObject(
                "SELECT count(*) FROM conversations WHERE agent_id = ? AND channel_type = 'api'",
                Integer.class, agent.id());
        assertThat(apiConversations).isEqualTo(2);
    }

    @Test
    void ingest_forAgentNotVisibleToContext_throwsAgentNotFoundAndPersistsNothing() {
        // clientB cannot see clientA's agent; the whole ingest must roll back atomically.
        TenantContext.setCurrentTenantId(clientB.id());
        try {
            assertThatThrownBy(() ->
                    inboundMessageService.ingest(agent.id(), "api", "intruder", "Hola"))
                    .isInstanceOf(AgentNotFoundException.class);
        } finally {
            TenantContext.clear();
        }

        Integer conversations = jdbc.queryForObject(
                "SELECT count(*) FROM conversations WHERE external_identity_ref = 'intruder'", Integer.class);
        assertThat(conversations).isZero();
        Integer invocations = jdbc.queryForObject(
                "SELECT count(*) FROM pending_invocations", Integer.class);
        assertThat(invocations).isZero();
    }

    private InboundMessageResult ingestAs(UUID context, UUID agentId, String channelType,
                                          String externalIdentityRef, String content) {
        TenantContext.setCurrentTenantId(context);
        try {
            return inboundMessageService.ingest(agentId, channelType, externalIdentityRef, content);
        } finally {
            TenantContext.clear();
        }
    }
}
