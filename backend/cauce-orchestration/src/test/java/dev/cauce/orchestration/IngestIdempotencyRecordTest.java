package dev.cauce.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IngestIdempotencyRecordTest {

    private static final UUID ID = UUID.randomUUID();
    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final UUID CONVERSATION_ID = UUID.randomUUID();
    private static final UUID MESSAGE_ID = UUID.randomUUID();
    private static final UUID INVOCATION_ID = UUID.randomUUID();
    private static final Instant CREATED_AT = Instant.parse("2026-07-04T10:00:00Z");

    @Test
    void constructor_validPayload_exposesAllFields() {
        IngestIdempotencyRecord record = new IngestIdempotencyRecord(ID, AGENT_ID, "wamid-1",
                CONVERSATION_ID, MESSAGE_ID, INVOCATION_ID, CREATED_AT);

        assertThat(record.id()).isEqualTo(ID);
        assertThat(record.agentId()).isEqualTo(AGENT_ID);
        assertThat(record.idempotencyKey()).isEqualTo("wamid-1");
        assertThat(record.conversationId()).isEqualTo(CONVERSATION_ID);
        assertThat(record.messageId()).isEqualTo(MESSAGE_ID);
        assertThat(record.invocationId()).isEqualTo(INVOCATION_ID);
        assertThat(record.createdAt()).isEqualTo(CREATED_AT);
    }

    @Test
    void result_completeRecord_carriesTheThreeStoredIds() {
        IngestIdempotencyRecord record = new IngestIdempotencyRecord(ID, AGENT_ID, "wamid-1",
                CONVERSATION_ID, MESSAGE_ID, INVOCATION_ID, CREATED_AT);

        InboundMessageResult result = record.result();

        assertThat(result.conversationId()).isEqualTo(CONVERSATION_ID);
        assertThat(result.messageId()).isEqualTo(MESSAGE_ID);
        assertThat(result.invocationId()).isEqualTo(INVOCATION_ID);
    }

    @Test
    void constructor_nullResultIds_rejectsTheIncompleteLockState() {
        assertThatThrownBy(() -> new IngestIdempotencyRecord(ID, AGENT_ID, "wamid-1",
                null, MESSAGE_ID, INVOCATION_ID, CREATED_AT))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("conversationId");
        assertThatThrownBy(() -> new IngestIdempotencyRecord(ID, AGENT_ID, "wamid-1",
                CONVERSATION_ID, null, INVOCATION_ID, CREATED_AT))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("messageId");
        assertThatThrownBy(() -> new IngestIdempotencyRecord(ID, AGENT_ID, "wamid-1",
                CONVERSATION_ID, MESSAGE_ID, null, CREATED_AT))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("invocationId");
    }

    @Test
    void constructor_nullIdentity_rejectsEachField() {
        assertThatThrownBy(() -> new IngestIdempotencyRecord(null, AGENT_ID, "wamid-1",
                CONVERSATION_ID, MESSAGE_ID, INVOCATION_ID, CREATED_AT))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("id");
        assertThatThrownBy(() -> new IngestIdempotencyRecord(ID, null, "wamid-1",
                CONVERSATION_ID, MESSAGE_ID, INVOCATION_ID, CREATED_AT))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("agentId");
        assertThatThrownBy(() -> new IngestIdempotencyRecord(ID, AGENT_ID, null,
                CONVERSATION_ID, MESSAGE_ID, INVOCATION_ID, CREATED_AT))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("idempotencyKey");
        assertThatThrownBy(() -> new IngestIdempotencyRecord(ID, AGENT_ID, "wamid-1",
                CONVERSATION_ID, MESSAGE_ID, INVOCATION_ID, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("createdAt");
    }

    @Test
    void constructor_blankKey_throwsIllegalArgument() {
        assertThatThrownBy(() -> new IngestIdempotencyRecord(ID, AGENT_ID, "   ",
                CONVERSATION_ID, MESSAGE_ID, INVOCATION_ID, CREATED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
    }
}
