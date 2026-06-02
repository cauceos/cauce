package dev.cauce.api.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cauce.api.web.ApiErrorWriter;
import dev.cauce.core.apikey.ApiKey;
import dev.cauce.core.apikey.ApiKeyGenerator;
import dev.cauce.core.apikey.ApiKeyHasher;
import dev.cauce.core.tenant.TenantContext;
import dev.cauce.tenancy.ApiKeyService;
import dev.cauce.tenancy.apikey.ApiKeyCache;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates each HTTP request via a static API key carried in the
 * {@code Authorization: Bearer <key>} header.
 *
 * <p>Hot path (cache hit):
 * <pre>
 *   header -> SHA-256(plaintext) -> Caffeine lookup -> validate not expired
 *                                -> set TenantContext + Authentication -> chain
 * </pre>
 *
 * <p>Cold path (cache miss):
 * <pre>
 *   header -> validate format -> repository.findByKeyPrefixAndRevokedAtIsNull
 *          -> for each candidate: hasher.matches (HMAC) -> validate isActive
 *          -> cache.put -> set TenantContext + Authentication -> markAsUsed -> chain
 * </pre>
 *
 * <p>The cache avoids the per-request DB lookup and HMAC verification; the default 5-minute TTL
 * bounds staleness. Revocations call {@link ApiKeyCache#invalidateById} on the same instance so
 * they take effect at once.
 *
 * <p>Requests without an {@code Authorization} header (or with a non-{@code Bearer}
 * one) pass through unauthenticated; Spring Security then decides what to do based on
 * the configured access rules. A protected endpoint with no authentication lands in
 * {@link Http401AuthenticationEntryPoint}.
 *
 * <p>{@code TenantContext} and {@code SecurityContextHolder} are cleared in a
 * {@code finally} block so the worker thread does not carry state into the next
 * request it serves.
 */
@Component
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthenticationFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final ApiKeyService apiKeyService;
    private final ApiKeyHasher apiKeyHasher;
    private final ApiKeyCache apiKeyCache;
    private final ObjectMapper objectMapper;
    /** A precomputed HMAC, used only to spend comparable time on a prefix miss. */
    private final String dummyHash;

    public ApiKeyAuthenticationFilter(ApiKeyService apiKeyService,
                                      ApiKeyHasher apiKeyHasher,
                                      ApiKeyCache apiKeyCache,
                                      ObjectMapper objectMapper) {
        this.apiKeyService = apiKeyService;
        this.apiKeyHasher = apiKeyHasher;
        this.apiKeyCache = apiKeyCache;
        this.objectMapper = objectMapper;
        this.dummyHash = apiKeyHasher.hash("timing-equalizer-not-a-real-key");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            // No credential -> let the chain decide (permitAll endpoints pass through;
            // protected endpoints will be 401'd by the AuthenticationEntryPoint).
            chain.doFilter(request, response);
            return;
        }

        String plaintext = header.substring(BEARER_PREFIX.length());
        if (!ApiKeyGenerator.isValidFormat(plaintext)) {
            writeUnauthorized(response);
            return;
        }

        AuthenticatedKey authenticated = resolveFromCacheOrDatabase(plaintext);
        if (authenticated == null) {
            writeUnauthorized(response);
            return;
        }

        TenantContext.setCurrentTenantId(authenticated.tenantId);
        SecurityContextHolder.getContext().setAuthentication(
                new ApiKeyAuthenticationToken(authenticated.tenantId, authenticated.apiKeyId));
        try {
            if (!authenticated.cached) {
                // First time we see this key in this TTL window -> stamp last_used_at.
                // Cache hits skip this so a hot key does not cost an UPDATE per request.
                // TODO: move to an async batched update once we have a metrics path that
                // measures it; for v1.0 the UPDATE is on the cold path only.
                apiKeyService.markAsUsed(authenticated.apiKeyId);
            }
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
            SecurityContextHolder.clearContext();
        }
    }

    private AuthenticatedKey resolveFromCacheOrDatabase(String plaintext) {
        Optional<ApiKeyCache.CachedApiKey> hit = apiKeyCache.lookup(plaintext);
        if (hit.isPresent()) {
            ApiKeyCache.CachedApiKey cached = hit.get();
            if (cached.isExpired()) {
                // Expiry passed since we cached -> drop and fall through to the DB,
                // which will load the row and let isActive() reject it.
                apiKeyCache.invalidateById(cached.apiKeyId());
            } else {
                return new AuthenticatedKey(cached.apiKeyId(), cached.tenantId(), true);
            }
        }

        String prefix = plaintext.substring(0, 8);
        List<ApiKey> candidates = apiKeyService.findActiveByKeyPrefix(prefix);
        if (candidates.isEmpty()) {
            // No key shares this prefix. Spend comparable time on a dummy HMAC so the response
            // time cannot reveal whether a prefix exists (timing-oracle defence).
            apiKeyHasher.matches(plaintext, dummyHash);
            return null;
        }
        for (ApiKey candidate : candidates) {
            if (candidate.matches(plaintext, apiKeyHasher) && candidate.isActive()) {
                apiKeyCache.put(plaintext, candidate);
                return new AuthenticatedKey(candidate.id(), candidate.tenantId(), false);
            }
        }
        log.debug("API key with prefix {} did not match any of {} candidate(s)",
                prefix, candidates.size());
        return null;
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        // Same uniform body as the authentication entry point: a generic, non-revealing
        // message identical for every failure reason (bad format, unknown / revoked /
        // expired key), so callers cannot probe which keys exist.
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        ApiErrorWriter.write(response, HttpStatus.UNAUTHORIZED,
                Http401AuthenticationEntryPoint.ERROR_CODE, Http401AuthenticationEntryPoint.MESSAGE, objectMapper);
    }

    private record AuthenticatedKey(UUID apiKeyId, UUID tenantId, boolean cached) {
    }
}
