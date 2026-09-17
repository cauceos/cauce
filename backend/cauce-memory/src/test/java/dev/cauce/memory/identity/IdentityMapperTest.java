package dev.cauce.memory.identity;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cauce.core.identity.Identity;
import dev.cauce.core.identity.IdentityKind;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdentityMapperTest {

    private final IdentityMapper mapper = new IdentityMapper();

    @Test
    void toEntity_copiesEveryField() {
        Identity identity = Identity.create(UUID.randomUUID(), "telegram", IdentityKind.PROVIDER_USER_ID,
                "987654321");

        IdentityEntity entity = mapper.toEntity(identity);

        assertThat(entity.getId()).isEqualTo(identity.id());
        assertThat(entity.getTenantId()).isEqualTo(identity.tenantId());
        assertThat(entity.getChannelType()).isEqualTo("telegram");
        assertThat(entity.getKind()).isEqualTo(IdentityKind.PROVIDER_USER_ID);
        assertThat(entity.getValue()).isEqualTo("987654321");
        assertThat(entity.getCreatedAt()).isEqualTo(identity.createdAt());
    }

    @Test
    void toDomain_thenToEntity_roundTripsUnchanged() {
        Instant createdAt = Instant.parse("2026-09-17T10:00:00Z");
        IdentityEntity original = new IdentityEntity(UUID.randomUUID(), UUID.randomUUID(), "whatsapp",
                IdentityKind.PHONE_NUMBER, "+34612345678", createdAt);

        Identity domain = mapper.toDomain(original);
        IdentityEntity back = mapper.toEntity(domain);

        assertThat(domain.id()).isEqualTo(original.getId());
        assertThat(domain.tenantId()).isEqualTo(original.getTenantId());
        assertThat(domain.channelType()).isEqualTo("whatsapp");
        assertThat(domain.kind()).isEqualTo(IdentityKind.PHONE_NUMBER);
        assertThat(domain.value()).isEqualTo("+34612345678");
        assertThat(domain.createdAt()).isEqualTo(createdAt);

        assertThat(back.getId()).isEqualTo(original.getId());
        assertThat(back.getTenantId()).isEqualTo(original.getTenantId());
        assertThat(back.getChannelType()).isEqualTo(original.getChannelType());
        assertThat(back.getKind()).isEqualTo(original.getKind());
        assertThat(back.getValue()).isEqualTo(original.getValue());
        assertThat(back.getCreatedAt()).isEqualTo(original.getCreatedAt());
    }
}
