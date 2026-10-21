package dev.cauce.business.audit;

import dev.cauce.core.audit.AuditContentHash;
import dev.cauce.core.audit.AuditEvent;
import dev.cauce.core.audit.CanonicalJson;
import dev.cauce.core.business.booking.Agenda;
import dev.cauce.core.business.catalog.Catalog;
import dev.cauce.core.business.profile.BusinessProfile;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The auditable vocabulary owned by cauce-business (ADR 0007). Each constant lives with its
 * emitter, like the tenancy, orchestration and channels factories, and each event lands in the
 * chain of the business it concerns, with the acting tenant as {@code actor_tenant_id} where an
 * administrator acted.
 *
 * <p>Never in a payload: the facts of a profile, the items of a catalog, a customer's name,
 * phone or words. Business content enters only as a content hash bound to the tenant
 * ({@link AuditContentHash}), so erasing the business rows never touches the chain.
 */
public final class BusinessAuditEvents {

    public static final String PROFILE_UPDATED = "admin.business_profile.updated";
    public static final String CATALOG_UPDATED = "admin.catalog.updated";
    public static final String AGENDA_UPDATED = "admin.agenda.updated";

    private BusinessAuditEvents() {
    }

    /** The business profile was loaded or replaced: its content by hash, the fact count. */
    public static AuditEvent profileUpdated(BusinessProfile profile, Map<String, Object> document,
                                            UUID actorTenantId) {
        Objects.requireNonNull(profile, "profile must not be null");
        Objects.requireNonNull(actorTenantId, "actorTenantId must not be null");
        return new AuditEvent(profile.tenantId(), PROFILE_UPDATED, Map.of(
                "business_profile_id", profile.id().toString(),
                "tenant_id", profile.tenantId().toString(),
                "content_hash", AuditContentHash.of(profile.tenantId(), CanonicalJson.serialize(document)),
                "fact_count", profile.facts().size(),
                "actor_tenant_id", actorTenantId.toString()));
    }

    /** The catalog was loaded or replaced: its content by hash, the item count. */
    public static AuditEvent catalogUpdated(Catalog catalog, Map<String, Object> document, UUID actorTenantId) {
        Objects.requireNonNull(catalog, "catalog must not be null");
        Objects.requireNonNull(actorTenantId, "actorTenantId must not be null");
        return new AuditEvent(catalog.tenantId(), CATALOG_UPDATED, Map.of(
                "catalog_id", catalog.id().toString(),
                "tenant_id", catalog.tenantId().toString(),
                "content_hash", AuditContentHash.of(catalog.tenantId(), CanonicalJson.serialize(document)),
                "item_count", catalog.items().size(),
                "declared_attributes", catalog.declaredAttributes(),
                "actor_tenant_id", actorTenantId.toString()));
    }

    /** The agenda was loaded or replaced: its settings by hash, the shift count. */
    public static AuditEvent agendaUpdated(Agenda agenda, Map<String, Object> document, UUID actorTenantId) {
        Objects.requireNonNull(agenda, "agenda must not be null");
        Objects.requireNonNull(actorTenantId, "actorTenantId must not be null");
        return new AuditEvent(agenda.tenantId(), AGENDA_UPDATED, Map.of(
                "agenda_id", agenda.id().toString(),
                "tenant_id", agenda.tenantId().toString(),
                "content_hash", AuditContentHash.of(agenda.tenantId(), CanonicalJson.serialize(document)),
                "shift_count", agenda.shifts().size(),
                "actor_tenant_id", actorTenantId.toString()));
    }
}
