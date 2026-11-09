package dev.cauce.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.cauce.core.agent.Agent;
import dev.cauce.core.agent.AgentNotFoundException;
import dev.cauce.core.identity.IdentityKind;
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

    @Autowired
    private dev.cauce.tenancy.ConversationService conversationService;

    private JdbcTemplate jdbc;

    private Tenant operator;
    private Tenant partner;
    private Tenant clientA;
    private Tenant clientB;
    private Agent agent; // owned by clientA

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(adminDataSource);
        jdbc.execute("TRUNCATE TABLE audit_chain_heads, audit_log_entries, audit_outbox, llm_usage_records, channel_inbound_receipts, channel_configs, whatsapp_apps, api_keys, ingest_idempotency_records, pending_invocations, messages, conversations, identities, agents, tenants CASCADE");
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
                "SELECT i.channel_type FROM conversations c JOIN identities i ON i.id = c.identity_id WHERE c.id = ?", String.class, result.conversationId());
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
    void ingest_afterTheWindowWithoutAConversationalMessage_closesTheThread_andOpensANewOne() {
        InboundMessageResult first = ingestAs(clientA.id(), agent.id(), "api", "user-1", "Hola");
        answered(first, 25);

        InboundMessageResult second = ingestAs(clientA.id(), agent.id(), "api", "user-1", "¿Seguís abiertos?");

        assertThat(second.conversationId()).isNotEqualTo(first.conversationId());
        assertThat(jdbc.queryForObject("SELECT status FROM conversations WHERE id = ?", String.class,
                first.conversationId())).isEqualTo("CLOSED");
        assertThat(jdbc.queryForObject("SELECT closed_at IS NOT NULL AND closed_at >= now() - interval '1 minute' "
                + "FROM conversations WHERE id = ?", Boolean.class, first.conversationId())).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM conversations WHERE id = ?", String.class,
                second.conversationId())).isEqualTo("OPEN");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM messages WHERE conversation_id = ? AND role = 'USER'",
                Integer.class, second.conversationId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_outbox WHERE event_type = 'conduct.conversation.closed'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void ingest_withinTheWindow_orWithAWindowOf0_keepsTheThread() {
        InboundMessageResult first = ingestAs(clientA.id(), agent.id(), "api", "user-1", "Hola");
        answered(first, 23);
        assertThat(ingestAs(clientA.id(), agent.id(), "api", "user-1", "Otra").conversationId())
                .isEqualTo(first.conversationId());

        jdbc.update("UPDATE agents SET new_thread_after_hours = 0 WHERE id = ?", agent.id());
        jdbc.update("UPDATE pending_invocations SET status = 'COMPLETED', completed_at = now() WHERE conversation_id = ?", first.conversationId());
        jdbc.update("UPDATE messages SET created_at = now() - interval '30 days' WHERE conversation_id = ?",
                first.conversationId());
        assertThat(ingestAs(clientA.id(), agent.id(), "api", "user-1", "Un mes después").conversationId())
                .isEqualTo(first.conversationId());
    }

    @Test
    void ingest_countsOnlyConversationalMessages_aFallbackAsOne_aSystemRecordNot() {
        InboundMessageResult first = ingestAs(clientA.id(), agent.id(), "api", "user-1", "Hola");
        answered(first, 30);
        // A recent SYSTEM record does not keep the thread open (nor would a tool call or result).
        insertMessage(first.conversationId(), "SYSTEM", "[orchestration_error] x", 1);
        InboundMessageResult afterSystem = ingestAs(clientA.id(), agent.id(), "api", "user-1", "Hola otra vez");
        assertThat(afterSystem.conversationId()).isNotEqualTo(first.conversationId());

        // A recent FALLBACK does: the person received it.
        answered(afterSystem, 30);
        insertMessage(afterSystem.conversationId(), "FALLBACK", "Ahora no puedo responder.", 1);
        assertThat(ingestAs(clientA.id(), agent.id(), "api", "user-1", "¿Y ahora?").conversationId())
                .isEqualTo(afterSystem.conversationId());
    }

    @Test
    void closeIfInactive_underAnotherBusinesssContext_doesNotSeeTheThread_underRls() {
        InboundMessageResult first = ingestAs(clientA.id(), agent.id(), "api", "user-1", "Hola");
        answered(first, 48);

        TenantContext.setCurrentTenantId(clientB.id());
        try {
            assertThat(conversationService.closeIfInactive(first.conversationId()))
                    .isInstanceOf(dev.cauce.tenancy.InactivityCheck.NoLongerOpen.class);
        } finally {
            TenantContext.clear();
        }
        assertThat(jdbc.queryForObject("SELECT status FROM conversations WHERE id = ?", String.class,
                first.conversationId())).isEqualTo("OPEN");
    }

    @Test
    void ingest_whileAnInvocationOfTheThreadIsInFlight_neverClosesIt() {
        InboundMessageResult first = ingestAs(clientA.id(), agent.id(), "api", "user-1", "Hola");
        // Still PENDING, as if the worker were stuck: the thread is old but its reply is coming.
        jdbc.update("UPDATE messages SET created_at = now() - interval '3 days' WHERE conversation_id = ?",
                first.conversationId());

        assertThat(ingestAs(clientA.id(), agent.id(), "api", "user-1", "¿Hola?").conversationId())
                .isEqualTo(first.conversationId());
    }

    /** The invocation of {@code ingested} completed and the agent answered, all {@code hoursAgo}. */
    private void answered(InboundMessageResult ingested, int hoursAgo) {
        jdbc.update("UPDATE pending_invocations SET status = 'COMPLETED', completed_at = now() WHERE conversation_id = ?",
                ingested.conversationId());
        insertMessage(ingested.conversationId(), "AGENT", "¡Hola! ¿En qué te ayudo?", 0);
        jdbc.update("UPDATE messages SET created_at = now() - make_interval(hours => ?) WHERE conversation_id = ?",
                hoursAgo, ingested.conversationId());
        jdbc.update("UPDATE conversations SET started_at = now() - make_interval(hours => ?) WHERE id = ?",
                hoursAgo, ingested.conversationId());
    }

    private void insertMessage(UUID conversationId, String role, String text, int minutesAgo) {
        // A FALLBACK carries why the invocation failed (V28).
        jdbc.update("INSERT INTO messages (id, conversation_id, role, parts, failure_type, created_at) VALUES (?, ?, ?, "
                        + "jsonb_build_array(jsonb_build_object('type', 'text', 'text', ?::text)), ?, "
                        + "now() - make_interval(mins => ?))",
                UUID.randomUUID(), conversationId, role, text, "FALLBACK".equals(role) ? "LLM_ERROR" : null,
                minutesAgo);
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
                "SELECT count(*) FROM conversations c JOIN identities i ON i.id = c.identity_id WHERE c.agent_id = ? AND i.channel_type = 'api'",
                Integer.class, agent.id());
        assertThat(apiConversations).isEqualTo(2);
    }

    @Test
    void ingest_forAgentNotVisibleToContext_throwsAgentNotFoundAndPersistsNothing() {
        // clientB cannot see clientA's agent; the whole ingest must roll back atomically.
        TenantContext.setCurrentTenantId(clientB.id());
        try {
            assertThatThrownBy(() ->
                    inboundMessageService.ingest(agent.id(), "api", null, IdentityKind.CLIENT_REFERENCE, "intruder", "Hola"))
                    .isInstanceOf(AgentNotFoundException.class);
        } finally {
            TenantContext.clear();
        }

        Integer conversations = jdbc.queryForObject(
                "SELECT count(*) FROM conversations c JOIN identities i ON i.id = c.identity_id "
                        + "WHERE i.value = 'intruder'", Integer.class);
        assertThat(conversations).isZero();
        // The agent is resolved before the identity: an invisible agent mints no identity.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identities WHERE value = 'intruder'",
                Integer.class)).isZero();
        Integer invocations = jdbc.queryForObject(
                "SELECT count(*) FROM pending_invocations", Integer.class);
        assertThat(invocations).isZero();
    }

    private InboundMessageResult ingestAs(UUID context, UUID agentId, String channelType,
                                          String externalIdentityRef, String content) {
        TenantContext.setCurrentTenantId(context);
        try {
            // Every caller ingests on the api channel: no config, a client reference.
            return inboundMessageService.ingest(agentId, channelType, null, IdentityKind.CLIENT_REFERENCE,
                    externalIdentityRef, content);
        } finally {
            TenantContext.clear();
        }
    }
}
