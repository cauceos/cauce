package dev.cauce.channels.whatsapp.app;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for WhatsApp apps: RLS-filtered like every other, plus the webhook's
 * tenant-less resolution through the V30 {@code SECURITY DEFINER} function (ADR 0001).
 */
public interface WhatsAppAppRepository extends JpaRepository<WhatsAppAppEntity, UUID> {

    /** The ACTIVE app {@code appId} regardless of tenant context; empty if absent or disabled. */
    @Query(value = "SELECT * FROM resolve_active_whatsapp_app(:appId)", nativeQuery = true)
    Optional<WhatsAppAppEntity> resolveActiveApp(@Param("appId") UUID appId);
}
