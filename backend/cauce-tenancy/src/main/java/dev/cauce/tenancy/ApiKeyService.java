package dev.cauce.tenancy;

import dev.cauce.core.apikey.ApiKey;
import dev.cauce.core.apikey.ApiKeyGenerator;
import dev.cauce.core.apikey.ApiKeyHasher;
import dev.cauce.core.apikey.ApiKeyNotFoundException;
import dev.cauce.core.tenant.TenantNotFoundException;
import dev.cauce.memory.apikey.ApiKeyEntity;
import dev.cauce.memory.apikey.ApiKeyMapper;
import dev.cauce.memory.apikey.ApiKeyRepository;
import dev.cauce.memory.tenant.TenantRepository;
import dev.cauce.tenancy.apikey.ApiKeyCache;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for managing {@link ApiKey}s. Every method is tenant-scoped:
 * {@code RlsContextAspect} establishes the RLS context from {@code TenantContext}
 * before each transactional method runs, and Row-Level Security filters every query
 * by the visibility of the owning tenant.
 *
 * <p>The plaintext of a newly-minted key is returned to the caller exactly once, via
 * {@link ApiKeyCreationResult}, and is then unrecoverable: only {@code keyHash} and
 * {@code keyPrefix} survive in the database.
 *
 * <p>Verification (matching an incoming plaintext to a stored hash) deliberately
 * lives outside this service: the authentication filter in cauce-api drives it,
 * because the lookup is keyed by {@code keyPrefix} and not by id and because the
 * filter must also push the result through {@link ApiKeyCache}.
 */
@Service
public class ApiKeyService {

    private final ApiKeyRepository apiKeyRepository;
    private final TenantRepository tenantRepository;
    private final ApiKeyMapper apiKeyMapper;
    private final ApiKeyHasher apiKeyHasher;
    private final ApiKeyCache apiKeyCache;

    public ApiKeyService(ApiKeyRepository apiKeyRepository,
                         TenantRepository tenantRepository,
                         ApiKeyMapper apiKeyMapper,
                         ApiKeyHasher apiKeyHasher,
                         ApiKeyCache apiKeyCache) {
        this.apiKeyRepository = apiKeyRepository;
        this.tenantRepository = tenantRepository;
        this.apiKeyMapper = apiKeyMapper;
        this.apiKeyHasher = apiKeyHasher;
        this.apiKeyCache = apiKeyCache;
    }

    /**
     * Mints a fresh API key owned by {@code tenantId}. {@code tenantId} must be visible
     * under the current {@code TenantContext}; an invisible or non-existent tenant
     * surfaces as {@link TenantNotFoundException} (the two cases are deliberately not
     * distinguished, to avoid leaking the existence of out-of-scope rows).
     *
     * <p>The returned {@link ApiKeyCreationResult} carries both the persisted
     * {@link ApiKey} and the {@code plaintextKey} — the only opportunity to read the
     * latter, since the database only stores its bcrypt hash.
     */
    @Transactional
    public ApiKeyCreationResult createApiKey(UUID tenantId, String name) {
        tenantRepository.findById(tenantId).orElseThrow(() ->
                new TenantNotFoundException("No tenant found for id " + tenantId));
        String plaintext = ApiKeyGenerator.newKey();
        ApiKey apiKey = ApiKey.create(tenantId, name, plaintext, apiKeyHasher);
        ApiKey saved = apiKeyMapper.toDomain(
                apiKeyRepository.save(apiKeyMapper.toEntity(apiKey)));
        return new ApiKeyCreationResult(saved, plaintext);
    }

    /**
     * Revokes the key with the given id. Revocation is a one-way transition; calling
     * this twice surfaces {@link dev.cauce.core.apikey.ApiKeyAlreadyRevokedException}
     * from the domain. The cache is invalidated for this id so the revocation takes
     * effect immediately instead of waiting for the TTL.
     */
    @Transactional
    public ApiKey revokeApiKey(UUID apiKeyId) {
        ApiKey revoked = loadVisible(apiKeyId).revoke();
        ApiKey saved = apiKeyMapper.toDomain(
                apiKeyRepository.save(apiKeyMapper.toEntity(revoked)));
        apiKeyCache.invalidateById(apiKeyId);
        return saved;
    }

    /** Lists all keys for {@code tenantId}; RLS filters by visibility. */
    @Transactional(readOnly = true)
    public List<ApiKey> listApiKeysForTenant(UUID tenantId) {
        return apiKeyRepository.findByTenantId(tenantId).stream()
                .map(apiKeyMapper::toDomain)
                .toList();
    }

    /**
     * Stamps {@code lastUsedAt = now} on the given key. Called by the authentication
     * filter on a cache miss only — cache hits skip this to avoid an UPDATE per
     * request. The caller must already have set {@code TenantContext} to the key's
     * owning tenant.
     */
    @Transactional
    public void markAsUsed(UUID apiKeyId) {
        ApiKey used = loadVisible(apiKeyId).markAsUsed();
        apiKeyRepository.save(apiKeyMapper.toEntity(used));
    }

    /**
     * Repository-level lookup used by the authentication filter. Returns the active
     * (not-revoked) keys sharing {@code keyPrefix}; the filter verifies bcrypt only
     * against this small candidate set. {@link dev.cauce.core.tenant.NoTenantContext}
     * is intentionally NOT applied: the filter calls this BEFORE setting
     * {@code TenantContext}, so the call runs in a no-context transaction and relies
     * on the (today-superuser) connection bypassing RLS.
     *
     * <p>TODO: when the application is wired to a least-privilege role, this lookup
     * needs a worker-style bypass mechanism (SECURITY DEFINER function or a dedicated
     * role with BYPASSRLS).
     */
    @Transactional(readOnly = true)
    @dev.cauce.core.tenant.NoTenantContext
    public List<ApiKey> findActiveByKeyPrefix(String keyPrefix) {
        return apiKeyRepository.findByKeyPrefixAndRevokedAtIsNull(keyPrefix).stream()
                .map(apiKeyMapper::toDomain)
                .toList();
    }

    private ApiKey loadVisible(UUID apiKeyId) {
        ApiKeyEntity entity = apiKeyRepository.findById(apiKeyId).orElseThrow(() ->
                new ApiKeyNotFoundException("No API key found for id " + apiKeyId));
        return apiKeyMapper.toDomain(entity);
    }
}
