package dev.cauce.governance.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cauce.governance.audit.AuditChainHead;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuditChainHeadMapperTest {

    private final AuditChainHeadMapper mapper = new AuditChainHeadMapper();

    @Test
    void roundTrip_preservesAllFields() {
        AuditChainHead original = AuditChainHead.of(UUID.randomUUID(), 42, "h".repeat(64));

        assertThat(mapper.toDomain(mapper.toEntity(original))).isEqualTo(original);
    }
}
