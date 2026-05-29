package dev.cauce.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.cauce.core.apikey.ApiKey;
import dev.cauce.core.apikey.ApiKeyGenerator;
import dev.cauce.core.apikey.ApiKeyHasher;
import dev.cauce.core.apikey.ApiKeyNotFoundException;
import dev.cauce.core.tenant.TenantNotFoundException;
import dev.cauce.core.tenant.Tier;
import dev.cauce.memory.apikey.ApiKeyEntity;
import dev.cauce.memory.apikey.ApiKeyMapper;
import dev.cauce.memory.apikey.ApiKeyRepository;
import dev.cauce.memory.tenant.TenantEntity;
import dev.cauce.memory.tenant.TenantRepository;
import dev.cauce.tenancy.apikey.ApiKeyCache;
import dev.cauce.tenancy.apikey.ApiKeyCacheProperties;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class ApiKeyServiceTest {

    private final UUID tenantId = UUID.randomUUID();

    private ApiKeyRepository apiKeyRepository;
    private TenantRepository tenantRepository;
    private ApiKeyMapper mapper;
    private ApiKeyHasher hasher;
    private ApiKeyCache cache;
    private ApiKeyService service;

    @BeforeEach
    void setUp() {
        apiKeyRepository = Mockito.mock(ApiKeyRepository.class);
        tenantRepository = Mockito.mock(TenantRepository.class);
        mapper = new ApiKeyMapper();
        hasher = new ApiKeyHasher() {
            @Override public String hash(String plaintext) { return "h:" + plaintext; }
            @Override public boolean matches(String p, String h) { return h.equals("h:" + p); }
        };
        ApiKeyCacheProperties properties = new ApiKeyCacheProperties();
        properties.setTtlSeconds(60);
        cache = new ApiKeyCache(properties);
        service = new ApiKeyService(apiKeyRepository, tenantRepository, mapper, hasher, cache);
        when(apiKeyRepository.save(any(ApiKeyEntity.class)))
                .thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void createApiKey_whenTenantExists_persistsAndReturnsPlaintext() {
        when(tenantRepository.findById(tenantId))
                .thenReturn(Optional.of(tenant()));

        ApiKeyCreationResult result = service.createApiKey(tenantId, "Production");

        assertThat(result.plaintextKey()).isNotNull();
        assertThat(ApiKeyGenerator.isValidFormat(result.plaintextKey())).isTrue();
        assertThat(result.apiKey().tenantId()).isEqualTo(tenantId);
        assertThat(result.apiKey().name()).isEqualTo("Production");
        assertThat(result.apiKey().keyPrefix()).isEqualTo(result.plaintextKey().substring(0, 8));
        // Hash stored, plaintext NOT stored anywhere on the domain object.
        assertThat(result.apiKey().keyHash()).isNotEqualTo(result.plaintextKey());
    }

    @Test
    void createApiKey_whenTenantNotFound_throwsTenantNotFound() {
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createApiKey(tenantId, "name"))
                .isInstanceOf(TenantNotFoundException.class);
    }

    @Test
    void revokeApiKey_persistsRevokedTransitionAndInvalidatesCache() {
        ApiKey existing = ApiKey.create(tenantId, "k", ApiKeyGenerator.newKey(), hasher);
        when(apiKeyRepository.findById(existing.id()))
                .thenReturn(Optional.of(mapper.toEntity(existing)));
        cache.put("ck_doesntmatter12345678901234567890", existing);

        ApiKey revoked = service.revokeApiKey(existing.id());

        assertThat(revoked.isRevoked()).isTrue();
        ArgumentCaptor<ApiKeyEntity> captor = ArgumentCaptor.forClass(ApiKeyEntity.class);
        verify(apiKeyRepository).save(captor.capture());
        assertThat(captor.getValue().getRevokedAt()).isNotNull();
        // The cache no longer carries the revoked id.
        assertThat(cache.size()).isZero();
    }

    @Test
    void revokeApiKey_whenNotFound_throwsApiKeyNotFound() {
        UUID id = UUID.randomUUID();
        when(apiKeyRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.revokeApiKey(id))
                .isInstanceOf(ApiKeyNotFoundException.class);
    }

    @Test
    void markAsUsed_persistsLastUsedAt() {
        ApiKey existing = ApiKey.create(tenantId, "k", ApiKeyGenerator.newKey(), hasher);
        when(apiKeyRepository.findById(existing.id()))
                .thenReturn(Optional.of(mapper.toEntity(existing)));

        service.markAsUsed(existing.id());

        ArgumentCaptor<ApiKeyEntity> captor = ArgumentCaptor.forClass(ApiKeyEntity.class);
        verify(apiKeyRepository).save(captor.capture());
        assertThat(captor.getValue().getLastUsedAt()).isNotNull();
    }

    @Test
    void listApiKeysForTenant_mapsResults() {
        ApiKey a = ApiKey.create(tenantId, "a", ApiKeyGenerator.newKey(), hasher);
        ApiKey b = ApiKey.create(tenantId, "b", ApiKeyGenerator.newKey(), hasher);
        when(apiKeyRepository.findByTenantId(tenantId))
                .thenReturn(List.of(mapper.toEntity(a), mapper.toEntity(b)));

        List<ApiKey> result = service.listApiKeysForTenant(tenantId);

        assertThat(result).extracting(ApiKey::id).containsExactly(a.id(), b.id());
    }

    @Test
    void findActiveByKeyPrefix_mapsAndReturnsCandidates() {
        ApiKey a = ApiKey.create(tenantId, "a", ApiKeyGenerator.newKey(), hasher);
        when(apiKeyRepository.findByKeyPrefixAndRevokedAtIsNull(a.keyPrefix()))
                .thenReturn(List.of(mapper.toEntity(a)));

        List<ApiKey> result = service.findActiveByKeyPrefix(a.keyPrefix());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo(a.id());
    }

    private TenantEntity tenant() {
        Instant now = Instant.now();
        return new TenantEntity(tenantId, null, Tier.OPERATOR, "Acme", now, now);
    }
}
