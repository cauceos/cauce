package dev.cauce.business.booking;

import dev.cauce.business.BusinessTenants;
import dev.cauce.business.audit.BusinessAuditEvents;
import dev.cauce.core.audit.AuditEventRecorder;
import dev.cauce.core.business.booking.Agenda;
import dev.cauce.core.business.booking.Agenda.Settings;
import dev.cauce.core.tenant.TenantContext;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Loads and replaces the agenda of a business (ADR 0007, B6). Tenant-scoped under RLS. */
@Service
public class AgendaService {

    private final AgendaRepository repository;
    private final AgendaMapper mapper;
    private final BusinessTenants businessTenants;
    private final AuditEventRecorder auditRecorder;

    public AgendaService(AgendaRepository repository, AgendaMapper mapper, BusinessTenants businessTenants,
                         AuditEventRecorder auditRecorder) {
        this.repository = repository;
        this.mapper = mapper;
        this.businessTenants = businessTenants;
        this.auditRecorder = auditRecorder;
    }

    /**
     * Loads or replaces the agenda of the business {@code tenantId}. Bookings already made are
     * kept: a shift that disappears keeps its bookings, which the owner sees and handles. Recorded
     * as {@code admin.agenda.updated} in the business's chain, the settings by hash only.
     *
     * @throws dev.cauce.core.tenant.TenantNotFoundException if the tenant is not visible
     * @throws dev.cauce.core.tenant.InvalidTenantTierException if it is not a business (CLIENT)
     */
    @Transactional
    public Agenda putAgenda(UUID tenantId, Settings settings) {
        businessTenants.requireBusiness(tenantId);
        Agenda agenda = repository.lockByTenantId(tenantId)
                .map(mapper::toDomain)
                .map(existing -> existing.replacedBy(settings))
                .orElseGet(() -> Agenda.create(tenantId, settings));
        Agenda saved = mapper.toDomain(repository.save(mapper.toEntity(agenda)));
        auditRecorder.record(BusinessAuditEvents.agendaUpdated(saved, document(saved),
                TenantContext.getCurrentTenantId().orElseThrow()));
        return saved;
    }

    /** The content the audit hash binds. */
    static Map<String, Object> document(Agenda agenda) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("unit", agenda.unit());
        document.put("max_party_size", agenda.maxPartySize());
        document.put("hold_minutes", agenda.holdMinutes());
        document.put("horizon_days", agenda.horizonDays());
        document.put("require_phone", agenda.requirePhone());
        document.put("shifts", agenda.shifts().stream().map(AgendaMapper::toMap).toList());
        document.put("closed_dates", agenda.closedDates().stream().map(Object::toString).toList());
        return document;
    }
}
