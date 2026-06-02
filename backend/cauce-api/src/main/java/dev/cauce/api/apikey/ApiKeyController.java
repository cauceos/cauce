package dev.cauce.api.apikey;

import dev.cauce.tenancy.ApiKeyService;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface for the API-key lifecycle: issue, list, revoke. Thin — it delegates to
 * {@link ApiKeyService}, whose RLS visibility IS the authority model (ADR 0002): a tenant may manage
 * keys for itself and its descendants. The acting tenant comes from the validated key
 * ({@code ApiKeyAuthenticationFilter}), never a client header.
 *
 * <p>Issuance is deliberately separate from tenant creation; no key is auto-minted. The plaintext is
 * returned once at creation and never again. Revocation is soft — the key simply stops
 * authenticating (the active-prefix lookup excludes revoked rows).
 */
@RestController
public class ApiKeyController {

    private static final String DEFAULT_LABEL = "api-key";

    private final ApiKeyService apiKeyService;

    public ApiKeyController(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    @PostMapping("/v1/tenants/{tenantId}/api-keys")
    public ResponseEntity<ApiKeyCreatedResponse> create(
            @PathVariable UUID tenantId,
            @RequestBody(required = false) CreateApiKeyRequest request) {
        String label = (request != null && request.label() != null && !request.label().isBlank())
                ? request.label().trim()
                : DEFAULT_LABEL;
        ApiKeyCreatedResponse created = ApiKeyCreatedResponse.from(apiKeyService.createApiKey(tenantId, label));
        return ResponseEntity.created(URI.create("/v1/api-keys/" + created.id())).body(created);
    }

    @GetMapping("/v1/tenants/{tenantId}/api-keys")
    public List<ApiKeyResponse> list(@PathVariable UUID tenantId) {
        return apiKeyService.listApiKeysForTenant(tenantId).stream().map(ApiKeyResponse::from).toList();
    }

    @DeleteMapping("/v1/api-keys/{keyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable UUID keyId) {
        apiKeyService.revokeApiKey(keyId);
    }
}
