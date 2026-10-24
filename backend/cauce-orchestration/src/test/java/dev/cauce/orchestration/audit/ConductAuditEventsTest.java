package dev.cauce.orchestration.audit;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cauce.core.audit.AuditContentHash;
import dev.cauce.core.audit.AuditEvent;
import dev.cauce.core.audit.AuditToolHash;
import dev.cauce.core.message.Message;
import dev.cauce.core.message.MessageRole;
import dev.cauce.core.tool.ToolCall;
import dev.cauce.core.tool.ToolResult;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConductAuditEventsTest {

    private final UUID tenantId = UUID.randomUUID();
    private final UUID invocationId = UUID.randomUUID();
    private final UUID agentId = UUID.randomUUID();
    private final UUID conversationId = UUID.randomUUID();
    private final UUID identityId = UUID.randomUUID();

    @Test
    void messageReceived_buildsPayloadWithContentHashNeverRawContentOrIdentity() {
        Message userMessage = Message.from(conversationId, MessageRole.USER, "Necesito ayuda");

        AuditEvent event = ConductAuditEvents.messageReceived(tenantId, invocationId, agentId,
                "telegram", identityId, userMessage);

        assertThat(event.tenantId()).isEqualTo(tenantId);
        assertThat(event.eventType()).isEqualTo("conduct.message.received");
        assertThat(event.payload())
                .containsEntry("invocation_id", invocationId.toString())
                .containsEntry("conversation_id", conversationId.toString())
                .containsEntry("message_id", userMessage.id().toString())
                .containsEntry("agent_id", agentId.toString())
                .containsEntry("channel_type", "telegram")
                .containsEntry("identity_id", identityId.toString())
                .containsEntry("content_hash", textHash(tenantId, "Necesito ayuda"))
                .containsEntry("content_hash_version", 2)
                .containsEntry("content_length", 14);
        // Forbidden fields: raw text and the (erasable) identity value never enter; the
        // interlocutor is the opaque identity_id only (PC-3).
        assertThat(event.payload().values()).doesNotContain("Necesito ayuda");
        assertThat(event.payload()).doesNotContainKeys("content", "external_identity_ref", "identity_value");
    }

    @Test
    void messageTranscribed_carriesWhoHowLongAndHashes_neverTheTranscript() {
        dev.cauce.core.message.MessagePart.Audio heard = dev.cauce.core.message.MessagePart.Audio
                .received("audio/ogg", "AwACAgQ-handle", null, 4)
                .transcribed("Mi teléfono es el 612 345 678", "b".repeat(64), 6200, 4);
        Message note = Message.fromParts(conversationId, MessageRole.USER, java.util.List.of(heard));

        AuditEvent event = ConductAuditEvents.messageTranscribed(tenantId, invocationId, agentId, note, heard,
                "whisper", "Systran/faster-whisper-small");

        assertThat(event.eventType()).isEqualTo("conduct.message.transcribed");
        assertThat(event.payload())
                .containsEntry("message_id", note.id().toString())
                .containsEntry("provider", "whisper")
                .containsEntry("duration_seconds", 4)
                .containsEntry("audio_sha256", "b".repeat(64))
                .containsEntry("content_hash", textHash(tenantId, "Mi teléfono es el 612 345 678"))
                .containsEntry("content_hash_version", 2);
        assertThat(event.payload().values()).doesNotContain("Mi teléfono es el 612 345 678", "AwACAgQ-handle");
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
                .containsEntry("content_hash", textHash(tenantId, "Claro, dime."))
                .containsEntry("content_hash_version", 2)
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
                ConductAuditEvents.DATA_ACCESS, ConductAuditEvents.REPLY_DELIVERED))
                .hasSize(3)
                .allSatisfy(type -> assertThat(type).startsWith("conduct."))
                .doesNotContain(ConductAuditEvents.MESSAGE_RECEIVED,
                        ConductAuditEvents.AGENT_RESPONDED,
                        ConductAuditEvents.INVOCATION_FAILED);
    }

    // --- tool calls and results (ADR 0005, C5) ---

    @Test
    void toolCalled_carriesTheCallsIdentityAndTheHashOfItsArguments_neverTheArguments() {
        ToolCall call = new ToolCall("call-1", "lookup_order", Map.of("order_id", "A-1234"));
        Message message = Message.toolCall(conversationId, call);

        AuditEvent event = ConductAuditEvents.toolCalled(tenantId, invocationId, agentId, message, call, 2);

        assertThat(event.eventType()).isEqualTo("conduct.tool.called");
        assertThat(event.payload())
                .containsEntry("tool_name", "lookup_order")
                .containsEntry("tool_call_id", "call-1")
                .containsEntry("message_id", message.id().toString())
                .containsEntry("round", 2)
                .containsEntry("arguments_hash", AuditToolHash.of(tenantId, Map.of("order_id", "A-1234")));
        assertThat(event.payload().values()).doesNotContain("A-1234");
        assertThat(event.payload()).doesNotContainKeys("input", "arguments");
    }

    @Test
    void toolReturned_carriesDurationOutcomeAndTheHashOfItsOutput_neverTheOutput() {
        ToolResult result = ToolResult.error("call-1", "lookup_order", "order A-1234 not found");
        Message message = Message.toolResult(conversationId, result);

        AuditEvent event = ConductAuditEvents.toolReturned(tenantId, invocationId, agentId, message, result, 2, 37L);

        assertThat(event.eventType()).isEqualTo("conduct.tool.returned");
        assertThat(event.payload())
                .containsEntry("tool_call_id", "call-1")
                .containsEntry("duration_ms", 37L)
                .containsEntry("outcome", "error")
                .containsEntry("output_hash", AuditToolHash.of(tenantId, "order A-1234 not found"));
        assertThat(event.payload().values()).doesNotContain("order A-1234 not found");
        assertThat(event.payload()).doesNotContainKeys("output");
    }

    /** The content hash v2 of a message of one text part, what the conduct payloads carry. */
    private static String textHash(java.util.UUID tenantId, String text) {
        return AuditContentHash.ofParts(tenantId,
                java.util.List.of(new dev.cauce.core.message.MessagePart.Text(text)));
    }
}
