package dev.cauce.memory.apikey;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link ApiKeyEntity}. Result sets are filtered by the
 * {@code api_keys} Row-Level Security policy according to the active tenant context.
 */
public interface ApiKeyRepository extends JpaRepository<ApiKeyEntity, UUID> {

    List<ApiKeyEntity> findByTenantId(UUID tenantId);

    /**
     * Returns the active (non-revoked) keys whose plaintext begins with {@code keyPrefix}.
     * The authentication filter calls this once per request, before any tenant context
     * exists, and verifies bcrypt only on the (typically 0-1) returned rows.
     *
     * <p>It must run regardless of tenant context (the lookup is what discovers the
     * tenant), so it goes through the {@code api_keys_active_by_prefix} SECURITY DEFINER
     * function (migration V11), which bypasses RLS as the owner instead of returning
     * nothing under the least-privilege {@code cauce_app} role. The {@code SELECT *}
     * projects the full {@code api_keys} row, so it maps straight back to
     * {@link ApiKeyEntity}.
     *
     * <p>Expiry is NOT filtered here: an expired-but-not-revoked row is still returned and
     * the caller checks {@link dev.cauce.core.apikey.ApiKey#isActive()} on the domain
     * object, which owns the "what counts as active" rule.
     */
    @Query(value = "SELECT * FROM api_keys_active_by_prefix(:keyPrefix)", nativeQuery = true)
    List<ApiKeyEntity> findByKeyPrefixAndRevokedAtIsNull(@Param("keyPrefix") String keyPrefix);
}
