package dev.cauce.api.security;

import dev.cauce.core.tenant.TenantContext;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only HTTP surface used by {@link ApiKeyAuthenticationFilterIT} to observe the
 * filter end-to-end. Loaded only under the {@code test} profile so it never reaches
 * production.
 *
 * <p>{@code GET /test/protected-resource}: returns the {@code tenantId} that the
 * filter set in {@link TenantContext}. A 200 with a non-null tenantId proves the
 * filter accepted the credential and propagated it.
 */
@RestController
@Profile("test")
public class TestAuthenticatedEndpoints {

    @GetMapping("/test/protected-resource")
    public Map<String, Object> whoami() {
        UUID tenantId = TenantContext.getCurrentTenantId().orElse(null);
        return Map.of("tenantId", tenantId == null ? "" : tenantId.toString());
    }
}
