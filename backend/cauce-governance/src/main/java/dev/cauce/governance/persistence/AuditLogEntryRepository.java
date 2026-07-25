package dev.cauce.governance.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for the append-only audit ledger. Insert-plus-read only by
 * construction: the database revokes UPDATE/DELETE from the runtime role (V21), so mutating
 * repository methods would fail at the DB layer — none are declared.
 */
public interface AuditLogEntryRepository extends JpaRepository<AuditLogEntryEntity, UUID> {

    /**
     * The tenant's highest assigned sequence number, empty for a tenant with no entries yet.
     * Since the chain unit this is only the one-time lazy-initialization fallback of the
     * drainer (a tenant with entries but no {@code audit_chain_heads} row yet); the head row
     * is the single source afterwards.
     */
    @Query("SELECT MAX(e.sequenceNumber) FROM AuditLogEntryEntity e WHERE e.tenantId = :tenantId")
    Optional<Long> findMaxSequenceNumber(@Param("tenantId") UUID tenantId);

    /** The tenant's full ledger in chain order, for the verifier. */
    List<AuditLogEntryEntity> findByTenantIdOrderBySequenceNumberAsc(UUID tenantId);
}
