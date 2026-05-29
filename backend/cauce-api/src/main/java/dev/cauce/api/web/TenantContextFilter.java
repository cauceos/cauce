package dev.cauce.api.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cauce.core.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * DEV-ONLY stopgap that establishes the {@link TenantContext} for {@code /v1/**} requests
 * from the {@code X-Tenant-Id} header.
 *
 * <p><strong>This is not authentication.</strong> It trusts a client-supplied header, which
 * is acceptable only until a real authentication layer exists. That layer will set the tenant
 * context from a validated principal (e.g. an API key) and this filter — together with the
 * {@code permitAll("/v1/**")} in {@code SecurityConfig} — will be removed.
 *
 * <p>Behaviour:
 * <ul>
 *   <li>header present and a valid UUID → context is set, then cleared in {@code finally};</li>
 *   <li>header absent → context is left unset, so the tenant-scoped service fail-closes with
 *       {@code MissingTenantContextException} (→ 401);</li>
 *   <li>header present but not a UUID → a 400 is written here directly, because an exception
 *       thrown from a filter does not reliably reach the {@code @RestControllerAdvice}.</li>
 * </ul>
 */
@Component
public class TenantContextFilter extends OncePerRequestFilter {

    public static final String TENANT_ID_HEADER = "X-Tenant-Id";
    private static final String API_PREFIX = "/v1/";

    private final ObjectMapper objectMapper;

    public TenantContextFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(API_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader(TENANT_ID_HEADER);
        UUID tenantId = null;
        if (header != null && !header.isBlank()) {
            try {
                tenantId = UUID.fromString(header.trim());
            } catch (IllegalArgumentException malformed) {
                ApiErrorWriter.write(response, HttpStatus.BAD_REQUEST,
                        "invalid_tenant_id", "X-Tenant-Id must be a valid UUID", objectMapper);
                return;
            }
        }

        if (tenantId != null) {
            TenantContext.setCurrentTenantId(tenantId);
        }
        try {
            chain.doFilter(request, response);
        } finally {
            if (tenantId != null) {
                TenantContext.clear();
            }
        }
    }
}
