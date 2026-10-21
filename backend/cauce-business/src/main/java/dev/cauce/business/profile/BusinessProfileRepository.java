package dev.cauce.business.profile;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository for business profiles; RLS-filtered like every other. */
public interface BusinessProfileRepository extends JpaRepository<BusinessProfileEntity, UUID> {

    Optional<BusinessProfileEntity> findByTenantId(UUID tenantId);
}
