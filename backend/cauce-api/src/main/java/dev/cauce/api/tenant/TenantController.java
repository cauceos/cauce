package dev.cauce.api.tenant;

import dev.cauce.core.tenant.Tenant;
import dev.cauce.tenancy.TenantService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface for tenants. Thin: validates and deserialises the request, delegates to
 * {@link TenantService} (which enforces tier rules and RLS), and maps the domain result to a
 * {@link TenantResponse}. The tenant context for these calls comes from the {@code X-Tenant-Id}
 * header via {@code TenantContextFilter} (a development stopgap).
 *
 * <p>Operator bootstrap is deliberately NOT exposed here.
 */
@RestController
@RequestMapping("/v1/tenants")
public class TenantController {

    private final TenantService tenantService;

    public TenantController(TenantService tenantService) {
        this.tenantService = tenantService;
    }

    @PostMapping("/partner")
    public ResponseEntity<TenantResponse> createPartner(@Valid @RequestBody CreatePartnerRequest request) {
        return created(tenantService.createPartner(request.name(), request.operatorId()));
    }

    @PostMapping("/client")
    public ResponseEntity<TenantResponse> createClient(@Valid @RequestBody CreateClientRequest request) {
        return created(tenantService.createClient(request.name(), request.partnerId()));
    }

    @GetMapping("/{id}")
    public TenantResponse get(@PathVariable UUID id) {
        return TenantResponse.from(tenantService.getTenant(id));
    }

    // TODO: paginate once child sets can grow large; returns all direct children for now.
    @GetMapping("/{id}/children")
    public List<TenantResponse> children(@PathVariable UUID id) {
        return tenantService.listChildren(id).stream().map(TenantResponse::from).toList();
    }

    private static ResponseEntity<TenantResponse> created(Tenant tenant) {
        return ResponseEntity.created(URI.create("/v1/tenants/" + tenant.id()))
                .body(TenantResponse.from(tenant));
    }
}
