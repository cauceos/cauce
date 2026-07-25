package dev.cauce.governance.persistence;

import dev.cauce.governance.audit.DrainStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Spring Data repository for audit outbox rows. The pending scan is tenant-scoped and runs
 * under RLS in the drained tenant's context; only {@link #pendingTenants()} crosses tenants,
 * through the V20 SECURITY DEFINER escape hatch (it returns tenant ids only — all row access
 * stays under RLS).
 */
public interface AuditOutboxEntryRepository extends JpaRepository<AuditOutboxEntryEntity, UUID> {

    /**
     * The oldest pending rows of one tenant, in capture order ({@code created_at} with the
     * UUIDv7 {@code id} as deterministic tiebreaker), limited to the drain batch.
     */
    List<AuditOutboxEntryEntity> findByTenantIdAndDrainStatusOrderByCreatedAtAscIdAsc(
            UUID tenantId, DrainStatus drainStatus, Limit limit);

    /**
     * Tenants that currently have PENDING outbox rows, via the V20 SECURITY DEFINER function
     * (the drainer polls without a tenant context, where RLS fail-closes to nothing).
     */
    @Query(value = "SELECT * FROM audit_outbox_pending_tenants()", nativeQuery = true)
    List<UUID> pendingTenants();
}
