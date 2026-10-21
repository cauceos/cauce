package dev.cauce.business.handoff;

import dev.cauce.core.business.handoff.Handoff;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository for handoffs; RLS-filtered like every other. */
public interface HandoffRepository extends JpaRepository<HandoffEntity, UUID> {

    boolean existsByTenantIdAndCode(UUID tenantId, String code);

    List<HandoffEntity> findByTenantIdOrderByIdAsc(UUID tenantId, Limit limit);

    List<HandoffEntity> findByTenantIdAndIdGreaterThanOrderByIdAsc(UUID tenantId, UUID afterId, Limit limit);

    List<HandoffEntity> findByTenantIdAndStatusOrderByIdAsc(UUID tenantId, Handoff.Status status, Limit limit);

    List<HandoffEntity> findByTenantIdAndStatusAndIdGreaterThanOrderByIdAsc(UUID tenantId, Handoff.Status status,
                                                                          UUID afterId, Limit limit);

    List<HandoffEntity> findByTenantIdAndStatusOrderByCreatedAtAsc(UUID tenantId, Handoff.Status status);
}
