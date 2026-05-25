package dev.cauce.orchestration.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cauce.orchestration.PendingInvocation;
import dev.cauce.orchestration.PendingInvocationStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PendingInvocationMapperTest {

    private final PendingInvocationMapper mapper = new PendingInvocationMapper();

    @Test
    void roundTrip_withNullableFieldsUnset_preservesState() {
        PendingInvocation original = PendingInvocation.create(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        PendingInvocation result = mapper.toDomain(mapper.toEntity(original));

        assertThat(result.id()).isEqualTo(original.id());
        assertThat(result.tenantId()).isEqualTo(original.tenantId());
        assertThat(result.conversationId()).isEqualTo(original.conversationId());
        assertThat(result.triggerMessageId()).isEqualTo(original.triggerMessageId());
        assertThat(result.status()).isEqualTo(PendingInvocationStatus.PENDING);
        assertThat(result.attemptCount()).isZero();
        assertThat(result.maxAttempts()).isEqualTo(original.maxAttempts());
        assertThat(result.lastAttemptAt()).isNull();
        assertThat(result.lastError()).isNull();
        assertThat(result.createdAt()).isEqualTo(original.createdAt());
        assertThat(result.claimedAt()).isNull();
        assertThat(result.claimedBy()).isNull();
        assertThat(result.completedAt()).isNull();
    }

    @Test
    void roundTrip_withAllFieldsSet_preservesState() {
        Instant now = Instant.now();
        PendingInvocation original = PendingInvocation.rehydrate(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                PendingInvocationStatus.FAILED, 2, 3, now, "boom", now, now, "worker-9", now);

        PendingInvocation result = mapper.toDomain(mapper.toEntity(original));

        assertThat(result.id()).isEqualTo(original.id());
        assertThat(result.status()).isEqualTo(PendingInvocationStatus.FAILED);
        assertThat(result.attemptCount()).isEqualTo(2);
        assertThat(result.maxAttempts()).isEqualTo(3);
        assertThat(result.lastAttemptAt()).isEqualTo(now);
        assertThat(result.lastError()).isEqualTo("boom");
        assertThat(result.createdAt()).isEqualTo(now);
        assertThat(result.claimedAt()).isEqualTo(now);
        assertThat(result.claimedBy()).isEqualTo("worker-9");
        assertThat(result.completedAt()).isEqualTo(now);
    }
}
