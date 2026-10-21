package dev.cauce.business.catalog;

import dev.cauce.business.BusinessTenants;
import dev.cauce.business.audit.BusinessAuditEvents;
import dev.cauce.core.audit.AuditEventRecorder;
import dev.cauce.core.business.catalog.Catalog;
import dev.cauce.core.business.catalog.CatalogItem;
import dev.cauce.core.business.catalog.CatalogSource;
import dev.cauce.core.tenant.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for catalogs and the native adapter of the {@link CatalogSource} port
 * (ADR 0007, B2). Tenant-scoped like {@code BusinessProfileService}.
 */
@Service
public class CatalogService implements CatalogSource {

    private final CatalogRepository repository;
    private final CatalogMapper mapper;
    private final BusinessTenants businessTenants;
    private final AuditEventRecorder auditRecorder;

    public CatalogService(CatalogRepository repository, CatalogMapper mapper, BusinessTenants businessTenants,
                          AuditEventRecorder auditRecorder) {
        this.repository = repository;
        this.mapper = mapper;
        this.businessTenants = businessTenants;
        this.auditRecorder = auditRecorder;
    }

    /**
     * Loads or replaces the catalog of the business {@code tenantId} as a whole. Recorded as
     * {@code admin.catalog.updated} in the business's chain, the content by hash only.
     *
     * @throws dev.cauce.core.tenant.TenantNotFoundException if the tenant is not visible
     * @throws dev.cauce.core.tenant.InvalidTenantTierException if it is not a business (CLIENT)
     * @throws IllegalArgumentException if the content breaks a domain rule
     */
    @Transactional
    public Catalog putCatalog(UUID tenantId, List<String> declaredAttributes, List<CatalogItem> items) {
        businessTenants.requireBusiness(tenantId);
        Catalog catalog = repository.findByTenantId(tenantId)
                .map(mapper::toDomain)
                .map(existing -> existing.replacedBy(declaredAttributes, items))
                .orElseGet(() -> Catalog.create(tenantId, declaredAttributes, items));
        Catalog saved = mapper.toDomain(repository.save(mapper.toEntity(catalog)));
        auditRecorder.record(BusinessAuditEvents.catalogUpdated(saved, document(saved),
                TenantContext.getCurrentTenantId().orElseThrow()));
        return saved;
    }

    /** The catalog of the business {@code tenantId}, if loaded and visible under the current context. */
    @Override
    @Transactional(readOnly = true)
    public Optional<Catalog> findCatalog(UUID tenantId) {
        return repository.findByTenantId(tenantId).map(mapper::toDomain);
    }

    /** The content the audit hash binds: the declared attributes and the items as stored. */
    static Map<String, Object> document(Catalog catalog) {
        return Map.of(
                "declared_attributes", catalog.declaredAttributes(),
                "items", catalog.items().stream().map(CatalogMapper::toMap).toList());
    }
}
