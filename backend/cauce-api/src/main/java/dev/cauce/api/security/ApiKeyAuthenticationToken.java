package dev.cauce.api.security;

import java.util.Collections;
import java.util.UUID;
import org.springframework.security.authentication.AbstractAuthenticationToken;

/**
 * Spring Security {@code Authentication} representing a successfully authenticated
 * API key. The principal is the owning {@code tenantId}; the {@code apiKeyId} is
 * carried as {@code details} for downstream audit logging.
 *
 * <p>No granted authorities in v1.0: every authenticated principal has the same role
 * (tenant-scoped). A future commit will layer role-based access on top of this.
 */
public class ApiKeyAuthenticationToken extends AbstractAuthenticationToken {

    private final UUID tenantId;
    private final UUID apiKeyId;

    public ApiKeyAuthenticationToken(UUID tenantId, UUID apiKeyId) {
        super(Collections.emptyList());
        this.tenantId = tenantId;
        this.apiKeyId = apiKeyId;
        setDetails(apiKeyId);
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return null; // the plaintext key is never retained past verification
    }

    @Override
    public Object getPrincipal() {
        return tenantId;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public UUID apiKeyId() {
        return apiKeyId;
    }
}
