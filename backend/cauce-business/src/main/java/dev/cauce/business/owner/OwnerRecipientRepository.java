package dev.cauce.business.owner;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data repository for owner recipients; RLS-filtered like every other. */
public interface OwnerRecipientRepository extends JpaRepository<OwnerRecipientEntity, UUID> {

    Optional<OwnerRecipientEntity> findByTenantId(UUID tenantId);

    /**
     * The tenant of channel config {@code configId}, if it is visible under the current context:
     * the owner bot a link code is issued for must belong to the business. Read under RLS; cauce-
     * business does not depend on cauce-channels, so it reads the one column it needs.
     */
    @Query(value = "SELECT tenant_id FROM channel_configs WHERE id = :configId AND status = 'ACTIVE'",
            nativeQuery = true)
    Optional<UUID> findActiveChannelConfigTenant(@Param("configId") UUID configId);
}
