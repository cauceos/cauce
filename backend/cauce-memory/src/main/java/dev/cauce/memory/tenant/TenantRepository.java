package dev.cauce.memory.tenant;

import dev.cauce.core.tenant.Tier;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link TenantEntity}. Derived queries only for now.
 */
public interface TenantRepository extends JpaRepository<TenantEntity, UUID> {

    List<TenantEntity> findByParentTenantId(UUID parentTenantId);

    List<TenantEntity> findByTier(Tier tier);
}
