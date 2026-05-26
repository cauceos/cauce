package dev.cauce.memory.apikey;

import dev.cauce.core.apikey.ApiKey;
import org.springframework.stereotype.Component;

/**
 * Hand-written bidirectional mapping between the domain {@link ApiKey} and its JPA
 * {@link ApiKeyEntity}. No external mapping library is used.
 */
@Component
public final class ApiKeyMapper {

    public ApiKeyEntity toEntity(ApiKey apiKey) {
        return new ApiKeyEntity(
                apiKey.id(),
                apiKey.tenantId(),
                apiKey.name(),
                apiKey.keyHash(),
                apiKey.keyPrefix(),
                apiKey.createdAt(),
                apiKey.lastUsedAt(),
                apiKey.revokedAt(),
                apiKey.expiresAt());
    }

    public ApiKey toDomain(ApiKeyEntity entity) {
        return ApiKey.rehydrate(
                entity.getId(),
                entity.getTenantId(),
                entity.getName(),
                entity.getKeyHash(),
                entity.getKeyPrefix(),
                entity.getCreatedAt(),
                entity.getLastUsedAt(),
                entity.getRevokedAt(),
                entity.getExpiresAt());
    }
}
