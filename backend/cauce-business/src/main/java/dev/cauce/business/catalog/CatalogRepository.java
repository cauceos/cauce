package dev.cauce.business.catalog;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository for catalogs; RLS-filtered like every other. */
public interface CatalogRepository extends JpaRepository<CatalogEntity, UUID> {

    Optional<CatalogEntity> findByTenantId(UUID tenantId);
}
