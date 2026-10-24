package dev.cauce.orchestration.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.cauce.core.agent.Agent;
import dev.cauce.core.conversation.Conversation;
import dev.cauce.core.identity.IdentityKind;
import dev.cauce.core.message.Message;
import dev.cauce.core.message.MessagePart;
import dev.cauce.core.message.MessageRole;
import dev.cauce.core.tenant.Tenant;
import dev.cauce.core.tenant.TenantContext;
import dev.cauce.core.UuidGenerator;
import dev.cauce.llm.model.FinishReason;
import dev.cauce.llm.model.LlmInvocation;
import dev.cauce.llm.model.LlmMessage;
import dev.cauce.llm.model.LlmResponse;
import dev.cauce.llm.model.LlmUsage;
import dev.cauce.orchestration.exception.VoiceNoteRejectedException;
import dev.cauce.orchestration.service.ConversationGateway;
import dev.cauce.orchestration.service.MockLlmProvider;
import dev.cauce.orchestration.service.OrchestratorService;
import dev.cauce.orchestration.support.AbstractOrchestrationIntegrationTest;
import dev.cauce.orchestration.support.FakeVoiceNotes;
import dev.cauce.orchestration.support.MockLlmProviderTestConfig;
import dev.cauce.tenancy.AgentService;
import dev.cauce.tenancy.ConversationService;
import dev.cauce.tenancy.TenantService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A voice note through the invocation, end to end on the database (ADR 0012, V2): the worker's
 * first step transcribes it, stores the transcript in its audio part under RLS, audits it by hash,
 * bills its seconds, and the model reads it marked as a transcribed voice note. The channel and the
 * transcriber are {@link FakeVoiceNotes}.
 */
@Import(MockLlmProviderTestConfig.class)
class VoiceNoteIT extends AbstractOrchestrationIntegrationTest {

    @Autowired private TenantService tenantService;
    @Autowired private AgentService agentService;
    @Autowired private ConversationService conversationService;
    @Autowired private ConversationGateway conversationGateway;
    @Autowired private OrchestratorService orchestratorService;
    @Autowired private MockLlmProvider mockLlmProvider;
    @Autowired private FakeVoiceNotes voiceNotes;

    private JdbcTemplate jdbc;
    private Tenant client;
    private Conversation conversation;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(adminDataSource);
        jdbc.execute("TRUNCATE TABLE audit_chain_heads, audit_log_entries, audit_outbox, llm_usage_records, channel_inbound_receipts, channel_configs, whatsapp_apps, api_keys, ingest_idempotency_records, pending_invocations, messages, conversations, identities, agents, tenants CASCADE");
        voiceNotes.reset();
        TenantContext.clear();
        Tenant operator = tenantService.bootstrapOperator("Operator");
        TenantContext.setCurrentTenantId(operator.id());
        Tenant partner = tenantService.createPartner("Partner", operator.id());
        TenantContext.setCurrentTenantId(partner.id());
        client = tenantService.createClient("Bar", partner.id());
        TenantContext.setCurrentTenantId(client.id());
        Agent agent = agentService.createAgent(client.id(), "Camarero", "Eres el camarero.", "anthropic",
                "claude-sonnet-4-7");
        UUID configId = UuidGenerator.newV7();
        jdbc.update("INSERT INTO channel_configs (id, tenant_id, agent_id, channel_type, credential, webhook_secret_hash, "
                + "status) VALUES (?, ?, ?, 'telegram', 'token', 'hash', 'ACTIVE')", configId, client.id(), agent.id());
        conversation = conversationService.startConversation(agent.id(), "telegram", IdentityKind.PROVIDER_USER_ID,
                "987654321", configId);
        TenantContext.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        voiceNotes.reset();
    }

    @Test
    void aVoiceNote_isTranscribedStoredAuditedAndBilled_andTheModelReadsItMarked() {
        voiceNotes.hear("Quería mesa para cuatro el sábado", 4);
        AtomicReference<LlmInvocation> sent = new AtomicReference<>();
        mockLlmProvider.respondWith(invocation -> {
            sent.set(invocation);
            return new LlmResponse("¿A qué hora?", List.of(), FinishReason.STOP, LlmUsage.of(1, 1));
        });
        Message note = append(MessagePart.Audio.received("audio/ogg", "AwACAgQ", 6200L, 4));

        respond(note);

        List<LlmMessage> history = sent.get().messages();
        // Stamped with when it was sent (ADR 0008, amendment), in UTC: this business has no profile.
        assertThat(history.get(history.size() - 1).content())
                .matches("\\[Sent \\w+ \\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2} UTC\\] "
                        + "\\[Transcribed voice note\\] Quería mesa para cuatro el sábado");
        Map<String, Object> part = jdbc.queryForMap("SELECT parts -> 0 ->> 'transcript' AS transcript, "
                + "parts -> 0 ->> 'sha256' AS sha256, (parts -> 0 ->> 'duration_seconds')::int AS seconds "
                + "FROM messages WHERE id = ?", note.id());
        assertThat(part.get("transcript")).isEqualTo("Quería mesa para cuatro el sábado");
        assertThat((String) part.get("sha256")).hasSize(64);
        assertThat(part.get("seconds")).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT payload::text FROM audit_outbox WHERE event_type = "
                + "'conduct.message.transcribed'", String.class))
                .contains("\"content_hash\"").doesNotContain("Quería", "AwACAgQ");
        assertThat(jdbc.queryForObject("SELECT audio_seconds FROM llm_usage_records WHERE provider = 'whisper'",
                Integer.class)).isEqualTo(4);
    }

    @Test
    void aRetriedInvocation_doesNotTranscribeTheNoteAgain() {
        Message note = append(MessagePart.Audio.received("audio/ogg", "AwACAgQ", null, 2));

        respond(note);
        respond(note);

        assertThat(voiceNotes.transcriptions()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM llm_usage_records WHERE provider = 'whisper'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void aVoiceNoteOverTwoMinutes_isRejected_beforeTheModelIsCalled() {
        AtomicReference<LlmInvocation> sent = new AtomicReference<>();
        mockLlmProvider.respondWith(invocation -> {
            sent.set(invocation);
            return new LlmResponse("no", List.of(), FinishReason.STOP, LlmUsage.of(1, 1));
        });
        Message note = append(MessagePart.Audio.received("audio/ogg", "AwACAgQ", null, 125));

        assertThatThrownBy(() -> respond(note)).isInstanceOf(VoiceNoteRejectedException.class);

        assertThat(sent.get()).isNull();
        assertThat(voiceNotes.transcriptions()).isZero();
    }

    private Message append(MessagePart.Audio note) {
        TenantContext.setCurrentTenantId(client.id());
        try {
            return conversationGateway.append(Message.fromParts(conversation.id(), MessageRole.USER, List.of(note)));
        } finally {
            TenantContext.clear();
        }
    }

    private void respond(Message trigger) {
        TenantContext.setCurrentTenantId(client.id());
        try {
            orchestratorService.respondToMessage(UUID.randomUUID(), conversation.id(), trigger.id());
        } finally {
            TenantContext.clear();
        }
    }
}
