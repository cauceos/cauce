package dev.cauce.memory.tenant;

import dev.cauce.core.tenant.Tier;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link TenantEntity}. Derived queries only for now.
 */
public interface TenantRepository extends JpaRepository<TenantEntity, UUID> {

    /** First keyset page of a tenant's direct children, ordered by id (UUIDv7: creation order). */
    List<TenantEntity> findByParentTenantIdOrderByIdAsc(UUID parentTenantId, Limit limit);

    /** Keyset page after the cursor: children with {@code id > afterId}, ordered by id. */
    List<TenantEntity> findByParentTenantIdAndIdGreaterThanOrderByIdAsc(
            UUID parentTenantId, UUID afterId, Limit limit);

    List<TenantEntity> findByTier(Tier tier);
}
