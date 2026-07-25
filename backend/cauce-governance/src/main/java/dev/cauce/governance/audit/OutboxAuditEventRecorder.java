package dev.cauce.governance.audit;

import dev.cauce.core.audit.AuditEvent;
import dev.cauce.core.audit.AuditEventRecorder;

import dev.cauce.governance.persistence.AuditOutboxEntryMapper;
import dev.cauce.governance.persistence.AuditOutboxEntryRepository;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link AuditEventRecorder} backed by the audit outbox: one INSERT that joins the caller's
 * transaction. {@code MANDATORY} propagation enforces the port contract at the framework
 * layer — calling this outside an active transaction throws instead of silently committing
 * the audit row on its own (which would break capture atomicity).
 *
 * <p>Deliberately a {@code @Component}, not a {@code @Service}: the RLS tenant GUC is set by
 * the calling service's own transaction (via {@code RlsContextAspect}), and the outbox INSERT
 * runs inside that same transaction under the caller's tenant context.
 */
@Component
public class OutboxAuditEventRecorder implements AuditEventRecorder {

    private final AuditOutboxEntryRepository repository;
    private final AuditOutboxEntryMapper mapper;

    public OutboxAuditEventRecorder(AuditOutboxEntryRepository repository,
                                    AuditOutboxEntryMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(AuditEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        repository.save(mapper.toEntity(AuditOutboxEntry.create(event)));
    }
}
