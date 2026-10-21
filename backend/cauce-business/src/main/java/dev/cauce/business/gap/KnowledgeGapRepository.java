package dev.cauce.business.gap;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository for unanswered questions; RLS-filtered like every other. */
public interface KnowledgeGapRepository extends JpaRepository<KnowledgeGapEntity, UUID> {

    List<KnowledgeGapEntity> findByTenantIdOrderByIdAsc(UUID tenantId, Limit limit);

    /** Keyset page after {@code afterId}; the comparison runs in Postgres (uuid order is bytewise). */
    List<KnowledgeGapEntity> findByTenantIdAndIdGreaterThanOrderByIdAsc(UUID tenantId, UUID afterId, Limit limit);

    List<KnowledgeGapEntity> findByTenantIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAsc(
            UUID tenantId, Instant from, Instant to);
}
