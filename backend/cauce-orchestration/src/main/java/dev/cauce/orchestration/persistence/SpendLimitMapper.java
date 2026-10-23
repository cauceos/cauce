package dev.cauce.orchestration.persistence;

import dev.cauce.orchestration.usage.SpendLimit;
import org.springframework.stereotype.Component;

/**
 * Hand-written bidirectional mapping between the domain {@link SpendLimit} and its JPA
 * {@link SpendLimitEntity}.
 */
@Component
public final class SpendLimitMapper {

    public SpendLimitEntity toEntity(SpendLimit limit) {
        return new SpendLimitEntity(limit.id(), limit.tenantId(), limit.monthlyLimitUsd(), limit.setByTenantId(),
                limit.updatedAt());
    }

    public SpendLimit toDomain(SpendLimitEntity entity) {
        return new SpendLimit(entity.getId(), entity.getTenantId(), entity.getMonthlyLimitUsd(),
                entity.getSetByTenantId(), entity.getUpdatedAt());
    }
}
