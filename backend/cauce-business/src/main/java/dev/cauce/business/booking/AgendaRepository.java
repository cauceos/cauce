package dev.cauce.business.booking;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data repository for agendas; RLS-filtered like every other. */
public interface AgendaRepository extends JpaRepository<AgendaEntity, UUID> {

    Optional<AgendaEntity> findByTenantId(UUID tenantId);

    /**
     * The agenda row locked FOR UPDATE until the transaction ends: every booking write takes it
     * before counting capacity, so two proposals for the last places of a shift run one after the
     * other (ADR 0007, B6).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM AgendaEntity a WHERE a.tenantId = :tenantId")
    Optional<AgendaEntity> lockByTenantId(@Param("tenantId") UUID tenantId);
}
