package dev.cauce.memory.apikey;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link ApiKeyEntity}. Result sets are filtered by the
 * {@code api_keys} Row-Level Security policy according to the active tenant context.
 */
public interface ApiKeyRepository extends JpaRepository<ApiKeyEntity, UUID> {

    List<ApiKeyEntity> findByTenantId(UUID tenantId);

    /**
     * Returns the active (non-revoked) keys whose plaintext begins with {@code keyPrefix}.
     * Backed by the partial index {@code idx_api_keys_prefix_active}. The
     * authentication filter calls this once per request and verifies bcrypt only on
     * the (typically 0-1) returned rows.
     *
     * <p>Expiry is NOT filtered here: an expired-but-not-revoked row is still returned
     * and the caller checks {@link dev.cauce.core.apikey.ApiKey#isActive()} on the
     * domain object. Two reasons: (a) PostgreSQL would have to evaluate {@code expires_at
     * <= now()} per row and that defeats the partial index; (b) the domain owns the
     * "what counts as active" rule.
     */
    List<ApiKeyEntity> findByKeyPrefixAndRevokedAtIsNull(String keyPrefix);
}
