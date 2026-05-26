package dev.cauce.memory.apikey;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cauce.core.apikey.ApiKey;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ApiKeyMapperTest {

    private final ApiKeyMapper mapper = new ApiKeyMapper();

    @Test
    void roundTrip_withNullableFieldsUnset_preservesState() {
        ApiKey original = ApiKey.rehydrate(UUID.randomUUID(), UUID.randomUUID(),
                "Production", "$2a$10$abcdef", "ck_abcde",
                Instant.now(), null, null, null);

        ApiKey result = mapper.toDomain(mapper.toEntity(original));

        assertThat(result.id()).isEqualTo(original.id());
        assertThat(result.tenantId()).isEqualTo(original.tenantId());
        assertThat(result.name()).isEqualTo("Production");
        assertThat(result.keyHash()).isEqualTo("$2a$10$abcdef");
        assertThat(result.keyPrefix()).isEqualTo("ck_abcde");
        assertThat(result.createdAt()).isEqualTo(original.createdAt());
        assertThat(result.lastUsedAt()).isNull();
        assertThat(result.revokedAt()).isNull();
        assertThat(result.expiresAt()).isNull();
    }

    @Test
    void roundTrip_withAllFieldsSet_preservesState() {
        Instant now = Instant.now();
        ApiKey original = ApiKey.rehydrate(UUID.randomUUID(), UUID.randomUUID(),
                "Production", "$2a$10$abcdef", "ck_abcde",
                now.minusSeconds(120), now.minusSeconds(60),
                now.minusSeconds(30), now.plusSeconds(60));

        ApiKey result = mapper.toDomain(mapper.toEntity(original));

        assertThat(result.lastUsedAt()).isEqualTo(original.lastUsedAt());
        assertThat(result.revokedAt()).isEqualTo(original.revokedAt());
        assertThat(result.expiresAt()).isEqualTo(original.expiresAt());
    }
}
