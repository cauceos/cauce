package dev.cauce.memory.tenant;

import dev.cauce.core.tenant.Tenant;
import org.springframework.stereotype.Component;

/**
 * Hand-written bidirectional mapping between the domain {@link Tenant} and its JPA
 * {@link TenantEntity}. No external mapping library is used.
 */
@Component
public final class TenantMapper {

    public TenantEntity toEntity(Tenant tenant) {
        return new TenantEntity(
                tenant.id(),
                tenant.parentTenantId(),
                tenant.tier(),
                tenant.name(),
                tenant.createdAt(),
                tenant.updatedAt());
    }

    public Tenant toDomain(TenantEntity entity) {
        return Tenant.rehydrate(
                entity.getId(),
                entity.getParentTenantId(),
                entity.getTier(),
                entity.getName(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
