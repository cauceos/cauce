package dev.cauce.governance.persistence;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

/**
 * Spring Data repository for the per-tenant chain heads (V23).
 */
public interface AuditChainHeadRepository extends JpaRepository<AuditChainHeadEntity, UUID> {

    /**
     * The tenant's head row, locked {@code FOR UPDATE}: one consistent read yields BOTH the
     * next sequence number and the next {@code prev_hash}, and the row lock serializes
     * concurrent drains of the same tenant without blocking other tenants.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<AuditChainHeadEntity> findByTenantId(UUID tenantId);
}
