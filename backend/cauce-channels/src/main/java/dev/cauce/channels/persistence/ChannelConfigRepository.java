package dev.cauce.channels.persistence;

import dev.cauce.channels.config.ChannelConfigStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link ChannelConfigEntity}. Result sets are filtered by the
 * channel_configs Row-Level Security policy according to the active tenant context.
 *
 * <p>{@link #resolveActiveChannelConfig} is the exception: the webhook path must resolve a
 * config — and thereby discover the owning tenant — <em>before</em> any tenant context
 * exists, so it goes through the owner-owned {@code SECURITY DEFINER} function
 * {@code resolve_active_channel_config} (V16), the narrow cross-tenant escape hatch
 * sanctioned by ADR 0001 (same pattern as the API-key prefix lookup, V11). The calling
 * service is {@code @NoTenantContext}; everything after resolution runs under RLS in the
 * config's tenant context.
 */
public interface ChannelConfigRepository extends JpaRepository<ChannelConfigEntity, UUID> {

    List<ChannelConfigEntity> findByAgentId(UUID agentId);

    List<ChannelConfigEntity> findByAgentIdAndChannelTypeAndStatus(
            UUID agentId, String channelType, ChannelConfigStatus status);

    /**
     * Resolves the ACTIVE config {@code configId} regardless of tenant context, via the
     * {@code resolve_active_channel_config} SECURITY DEFINER function (V16). Empty when
     * the config does not exist or is not ACTIVE — indistinguishable on purpose.
     */
    @Query(value = "SELECT * FROM resolve_active_channel_config(:configId)", nativeQuery = true)
    Optional<ChannelConfigEntity> resolveActiveChannelConfig(@Param("configId") UUID configId);
}
