package dev.cauce.orchestration.audit;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cauce.core.audit.AuditContentHash;
import dev.cauce.core.audit.AuditEvent;
import dev.cauce.core.message.Message;
import dev.cauce.core.message.MessageRole;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConductAuditEventsTest {

    private final UUID tenantId = UUID.randomUUID();
    private final UUID invocationId = UUID.randomUUID();
    private final UUID agentId = UUID.randomUUID();
    private final UUID conversationId = UUID.randomUUID();

    @Test
    void messageReceived_buildsPayloadWithContentHashNeverRawContentOrIdentity() {
        Message userMessage = Message.from(conversationId, MessageRole.USER, "Necesito ayuda");

        AuditEvent event = ConductAuditEvents.messageReceived(tenantId, invocationId, agentId,
                "telegram", userMessage);

        assertThat(event.tenantId()).isEqualTo(tenantId);
        assertThat(event.eventType()).isEqualTo("conduct.message.received");
        assertThat(event.payload())
                .containsEntry("invocation_id", invocationId.toString())
                .containsEntry("conversation_id", conversationId.toString())
                .containsEntry("message_id", userMessage.id().toString())
                .containsEntry("agent_id", agentId.toString())
                .containsEntry("channel_type", "telegram")
                .containsEntry("content_hash", AuditContentHash.of(tenantId, "Necesito ayuda"))
                .containsEntry("content_length", 14);
        // Forbidden fields: raw text and the (erasable) external identity never enter.
        assertThat(event.payload().values()).doesNotContain("Necesito ayuda");
        assertThat(event.payload()).doesNotContainKeys("content", "external_identity_ref");
    }

    @Test
    void agentResponded_buildsTerminalSuccessPayloadWithFinishReasonAndRounds() {
        Message reply = Message.from(conversationId, MessageRole.AGENT, "Claro, dime.");

        AuditEvent event = ConductAuditEvents.agentResponded(tenantId, invocationId, agentId,
                reply, "STOP", 3);

        assertThat(event.eventType()).isEqualTo("conduct.agent.responded");
        assertThat(event.payload())
                .containsEntry("invocation_id", invocationId.toString())
                .containsEntry("conversation_id", conversationId.toString())
                .containsEntry("message_id", reply.id().toString())
                .containsEntry("agent_id", agentId.toString())
                .containsEntry("finish_reason", "STOP")
                .containsEntry("rounds", 3)
                .containsEntry("content_hash", AuditContentHash.of(tenantId, "Claro, dime."))
                .containsEntry("content_length", 12);
        assertThat(event.payload().values()).doesNotContain("Claro, dime.");
    }

    @Test
    void invocationFailed_buildsTaxonomyOnlyPayloadWithoutErrorDetail() {
        AuditEvent event = ConductAuditEvents.invocationFailed(tenantId, invocationId,
                conversationId, "LLM_ERROR");

        assertThat(event.eventType()).isEqualTo("conduct.invocation.failed");
        assertThat(event.payload())
                .containsOnlyKeys("invocation_id", "conversation_id", "failure_type")
                .containsEntry("failure_type", "LLM_ERROR");
    }

    @Test
    void reservedHookTypes_areDefinedButDistinctFromEmittedTypes() {
        // The hooks exist as vocabulary (reserving a type today costs nothing; adding one
        // to a live chain later means re-doing it) and nothing in production emits them —
        // the conduct IT asserts the chain contains only emitted types.
        assertThat(java.util.Set.of(ConductAuditEvents.EXTERNAL_ACTION,
                ConductAuditEvents.DATA_ACCESS, ConductAuditEvents.HUMAN_ESCALATION,
                ConductAuditEvents.REPLY_DELIVERED))
                .hasSize(4)
                .allSatisfy(type -> assertThat(type).startsWith("conduct."))
                .doesNotContain(ConductAuditEvents.MESSAGE_RECEIVED,
                        ConductAuditEvents.AGENT_RESPONDED,
                        ConductAuditEvents.INVOCATION_FAILED);
    }
}
