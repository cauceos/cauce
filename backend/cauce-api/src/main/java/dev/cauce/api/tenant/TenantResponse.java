package dev.cauce.api.tenant;

import dev.cauce.core.tenant.Tenant;
import dev.cauce.core.tenant.Tier;
import java.time.Instant;
import java.util.UUID;

/**
 * API representation of a {@link Tenant}. Decoupled from the domain type so the wire contract
 * can evolve independently. Serialised in snake_case (global Jackson naming strategy).
 */
public record TenantResponse(
        UUID id,
        UUID parentTenantId,
        Tier tier,
        String name,
        Instant createdAt,
        Instant updatedAt) {

    public static TenantResponse from(Tenant tenant) {
        return new TenantResponse(
                tenant.id(),
                tenant.parentTenantId(),
                tenant.tier(),
                tenant.name(),
                tenant.createdAt(),
                tenant.updatedAt());
    }
}
