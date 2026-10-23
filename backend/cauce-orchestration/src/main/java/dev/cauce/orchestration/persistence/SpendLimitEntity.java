package dev.cauce.orchestration.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA persistence mapping for a tenant's spend limit (V48). The domain type is
 * {@link dev.cauce.orchestration.usage.SpendLimit}, converted by {@link SpendLimitMapper}.
 */
@Entity
@Table(name = "tenant_spend_limits")
public class SpendLimitEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "monthly_limit_usd", nullable = false, precision = 12, scale = 2)
    private BigDecimal monthlyLimitUsd;

    @Column(name = "set_by_tenant_id", nullable = false)
    private UUID setByTenantId;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SpendLimitEntity() {
        // for JPA
    }

    public SpendLimitEntity(UUID id, UUID tenantId, BigDecimal monthlyLimitUsd, UUID setByTenantId,
                            Instant updatedAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.monthlyLimitUsd = monthlyLimitUsd;
        this.setByTenantId = setByTenantId;
        this.updatedAt = updatedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public BigDecimal getMonthlyLimitUsd() {
        return monthlyLimitUsd;
    }

    public UUID getSetByTenantId() {
        return setByTenantId;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
