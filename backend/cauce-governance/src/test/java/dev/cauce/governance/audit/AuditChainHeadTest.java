package dev.cauce.governance.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuditChainHeadTest {

    private final UUID tenantId = UUID.randomUUID();

    @Test
    void of_mintsTheFirstHeadOfATenant() {
        AuditChainHead head = AuditChainHead.of(tenantId, 3, "h".repeat(64));

        assertThat(head.tenantId()).isEqualTo(tenantId);
        assertThat(head.lastSequenceNumber()).isEqualTo(3);
        assertThat(head.lastEntryHash()).isEqualTo("h".repeat(64));
        assertThat(head.updatedAt()).isNotNull();
    }

    @Test
    void advancedTo_movesSequenceAndHashKeepingTheTenant() {
        AuditChainHead head = AuditChainHead.of(tenantId, 3, "h".repeat(64));

        AuditChainHead advanced = head.advancedTo(7, "i".repeat(64));

        assertThat(advanced.tenantId()).isEqualTo(tenantId);
        assertThat(advanced.lastSequenceNumber()).isEqualTo(7);
        assertThat(advanced.lastEntryHash()).isEqualTo("i".repeat(64));
    }

    @Test
    void constructor_whenSequenceBelowOne_throwsIllegalArgument() {
        assertThatThrownBy(() -> AuditChainHead.of(tenantId, 0, "h".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lastSequenceNumber");
    }

    @Test
    void constructor_whenBlankHash_throwsIllegalArgument() {
        assertThatThrownBy(() -> AuditChainHead.of(tenantId, 1, " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lastEntryHash");
    }
}
