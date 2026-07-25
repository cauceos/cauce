package dev.cauce.core.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuditEventTest {

    private final UUID tenantId = UUID.randomUUID();

    @Test
    void constructor_withValidArguments_holdsThem() {
        AuditEvent event = new AuditEvent(tenantId, "placeholder.event", Map.of("k", "v"));

        assertThat(event.tenantId()).isEqualTo(tenantId);
        assertThat(event.eventType()).isEqualTo("placeholder.event");
        assertThat(event.payload()).containsExactlyEntriesOf(Map.of("k", "v"));
    }

    @Test
    void constructor_copiesPayloadDefensively() {
        Map<String, Object> mutable = new HashMap<>(Map.of("k", "v"));

        AuditEvent event = new AuditEvent(tenantId, "placeholder.event", mutable);
        mutable.put("sneaky", "late-addition");

        assertThat(event.payload()).containsOnlyKeys("k");
        assertThatThrownBy(() -> event.payload().put("more", "x"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void constructor_whenNullTenantId_throwsNpe() {
        assertThatThrownBy(() -> new AuditEvent(null, "placeholder.event", Map.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("tenantId");
    }

    @Test
    void constructor_whenBlankEventType_throwsIllegalArgument() {
        assertThatThrownBy(() -> new AuditEvent(tenantId, "  ", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventType");
    }

    @Test
    void constructor_whenNullPayload_throwsNpe() {
        assertThatThrownBy(() -> new AuditEvent(tenantId, "placeholder.event", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("payload");
    }
}
