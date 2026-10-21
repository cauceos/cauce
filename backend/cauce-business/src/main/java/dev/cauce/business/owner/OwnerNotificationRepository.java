package dev.cauce.business.owner;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for owner notifications: RLS-filtered like every other, plus the
 * dispatcher's cross-tenant claim through the V37 {@code SECURITY DEFINER} function (ADR 0001).
 */
public interface OwnerNotificationRepository extends JpaRepository<OwnerNotificationEntity, UUID> {

    List<OwnerNotificationEntity> findByTenantIdOrderByIdAsc(UUID tenantId, Limit limit);

    List<OwnerNotificationEntity> findByTenantIdAndIdGreaterThanOrderByIdAsc(UUID tenantId, UUID afterId, Limit limit);

    /** A claimed notice: its id and the tenant to send it under. */
    interface Claim {
        UUID getId();

        UUID getTenantId();
    }

    /** Leases up to {@code batchSize} due notices for {@code leaseSeconds}, across tenants. */
    @Query(value = "SELECT id, tenant_id AS tenantId FROM claim_due_owner_notifications(:batchSize, :leaseSeconds)",
            nativeQuery = true)
    List<Claim> claimDue(@Param("batchSize") int batchSize, @Param("leaseSeconds") int leaseSeconds);
}
