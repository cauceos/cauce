package dev.cauce.business.profile;

import dev.cauce.business.BusinessTenants;
import dev.cauce.business.audit.BusinessAuditEvents;
import dev.cauce.core.audit.AuditEventRecorder;
import dev.cauce.core.business.profile.BusinessProfile;
import dev.cauce.core.business.profile.BusinessProfile.Fact;
import dev.cauce.core.business.profile.BusinessProfileSource;
import dev.cauce.core.tenant.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for business profiles and the native adapter of the
 * {@link BusinessProfileSource} port (ADR 0007, B2). Tenant-scoped: {@code RlsContextAspect}
 * sets the RLS context from {@code TenantContext}, so a business outside the caller's scope is a
 * not-found, and a profile is visible to its business and the tenants above it.
 */
@Service
public class BusinessProfileService implements BusinessProfileSource {

    private final BusinessProfileRepository repository;
    private final BusinessProfileMapper mapper;
    private final BusinessTenants businessTenants;
    private final AuditEventRecorder auditRecorder;

    public BusinessProfileService(BusinessProfileRepository repository, BusinessProfileMapper mapper,
                                  BusinessTenants businessTenants, AuditEventRecorder auditRecorder) {
        this.repository = repository;
        this.mapper = mapper;
        this.businessTenants = businessTenants;
        this.auditRecorder = auditRecorder;
    }

    /**
     * Loads or replaces the profile of the business {@code tenantId} as a whole. Recorded as
     * {@code admin.business_profile.updated} in the business's chain, the content by hash only.
     *
     * @throws dev.cauce.core.tenant.TenantNotFoundException if the tenant is not visible
     * @throws dev.cauce.core.tenant.InvalidTenantTierException if it is not a business (CLIENT)
     * @throws IllegalArgumentException if the content breaks a domain rule
     */
    @Transactional
    public BusinessProfile putProfile(UUID tenantId, String name, String timeZone, String locale, List<Fact> facts) {
        businessTenants.requireBusiness(tenantId);
        BusinessProfile profile = repository.findByTenantId(tenantId)
                .map(mapper::toDomain)
                .map(existing -> existing.replacedBy(name, timeZone, locale, facts))
                .orElseGet(() -> BusinessProfile.create(tenantId, name, timeZone, locale, facts));
        BusinessProfile saved = mapper.toDomain(repository.save(mapper.toEntity(profile)));
        auditRecorder.record(BusinessAuditEvents.profileUpdated(saved, document(saved),
                TenantContext.getCurrentTenantId().orElseThrow()));
        return saved;
    }

    /** The profile of the business {@code tenantId}, if loaded and visible under the current context. */
    @Override
    @Transactional(readOnly = true)
    public Optional<BusinessProfile> findProfile(UUID tenantId) {
        return repository.findByTenantId(tenantId).map(mapper::toDomain);
    }

    /** The content the audit hash binds: everything the business stated, nothing derived. */
    static Map<String, Object> document(BusinessProfile profile) {
        return Map.of(
                "name", profile.name(),
                "time_zone", profile.timeZone().getId(),
                "locale", profile.locale(),
                "facts", profile.facts().stream().map(fact -> Map.of("key", fact.key(), "value", fact.value()))
                        .toList());
    }
}
