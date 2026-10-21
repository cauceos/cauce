package dev.cauce.business.owner;

import dev.cauce.business.audit.BusinessAuditEvents;
import dev.cauce.core.audit.AuditEventRecorder;
import dev.cauce.core.business.owner.OwnerNotifier.Delivery;
import dev.cauce.core.business.owner.OwnerNotifier.Recipient;
import dev.cauce.core.tenant.NoTenantContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The owner-notification outbox (ADR 0007, B9): queues a notice in the caller's transaction, lets
 * the dispatcher read what to send and record what happened, and lists the notices for the owner.
 * Tenant-scoped under RLS. A notice reaching a final state is audited as
 * {@code conduct.owner.notified}, with its status and reason only.
 */
@Service
public class OwnerNotificationService {

    private final OwnerNotificationRepository repository;
    private final OwnerNotificationMapper mapper;
    private final OwnerRecipientRepository recipientRepository;
    private final AuditEventRecorder auditRecorder;

    public OwnerNotificationService(OwnerNotificationRepository repository, OwnerNotificationMapper mapper,
                                    OwnerRecipientRepository recipientRepository, AuditEventRecorder auditRecorder) {
        this.repository = repository;
        this.mapper = mapper;
        this.recipientRepository = recipientRepository;
        this.auditRecorder = auditRecorder;
    }

    /**
     * Queues {@code content} for the owner of {@code tenantId}, joining the caller's transaction so
     * the notice commits with the fact it reports. With no owner linked it is recorded NOT_SENT
     * ({@link OwnerNotification#NO_OWNER_RECIPIENT}) and never attempted.
     */
    @Transactional
    public OwnerNotification queue(UUID tenantId, OwnerNotification.Kind kind, UUID subjectId, String content) {
        boolean ownerLinked = recipientRepository.findByTenantId(tenantId).isPresent();
        OwnerNotification saved = mapper.toDomain(repository.save(mapper.toEntity(
                OwnerNotification.queue(tenantId, kind, subjectId, content, ownerLinked))));
        if (!ownerLinked) {
            auditFinal(saved);
        }
        return saved;
    }

    /**
     * Leases up to {@code batchSize} due notices across tenants for {@code leaseSeconds}, returning
     * their ids and tenants. {@link NoTenantContext}: the dispatcher has no single tenant; the claim
     * runs in the V37 {@code SECURITY DEFINER} function, the narrow hatch of ADR 0001, and every
     * further step runs under the claimed notice's tenant.
     */
    @Transactional
    @NoTenantContext
    public List<OwnerNotificationRepository.Claim> claimDue(int batchSize, int leaseSeconds) {
        return repository.claimDue(batchSize, leaseSeconds);
    }

    /** A claimed notice ready to send: the notice and where its owner reads. */
    public record Send(OwnerNotification notification, Recipient recipient) {
    }

    /**
     * What to send for the claimed notice {@code id}: empty when it is no longer PENDING. With no
     * owner linked any more, it is closed NOT_SENT here and nothing is sent.
     */
    @Transactional
    public Optional<Send> prepare(UUID id) {
        Optional<OwnerNotification> notification = repository.findById(id).map(mapper::toDomain)
                .filter(n -> n.status() == OwnerNotification.Status.PENDING);
        if (notification.isEmpty()) {
            return Optional.empty();
        }
        Optional<OwnerRecipientEntity> recipient = recipientRepository.findByTenantId(notification.get().tenantId());
        if (recipient.isEmpty()) {
            close(notification.get().notSent(OwnerNotification.NO_OWNER_RECIPIENT));
            return Optional.empty();
        }
        OwnerRecipientEntity r = recipient.get();
        return Optional.of(new Send(notification.get(),
                new Recipient(r.getChannelConfigId(), r.getIdentityId(), r.getLinkedAt())));
    }

    /** Records the outcome of one send of the notice {@code id}, after at most {@code maxAttempts}. */
    @Transactional
    public OwnerNotification record(UUID id, Delivery delivery, int maxAttempts) {
        OwnerNotification current = repository.findById(id).map(mapper::toDomain).orElseThrow(() ->
                new IllegalStateException("notification " + id + " vanished while it was being sent"));
        return close(current.after(delivery, maxAttempts));
    }

    /**
     * Up to {@code maxRows} notices of the business {@code tenantId}, oldest first, after
     * {@code afterId} when non-null (keyset pagination).
     */
    @Transactional(readOnly = true)
    public List<OwnerNotification> listForTenant(UUID tenantId, UUID afterId, int maxRows) {
        List<OwnerNotificationEntity> page = afterId == null
                ? repository.findByTenantIdOrderByIdAsc(tenantId, Limit.of(maxRows))
                : repository.findByTenantIdAndIdGreaterThanOrderByIdAsc(tenantId, afterId, Limit.of(maxRows));
        return page.stream().map(mapper::toDomain).toList();
    }

    private OwnerNotification close(OwnerNotification updated) {
        OwnerNotification saved = mapper.toDomain(repository.save(mapper.toEntity(updated)));
        if (saved.status() != OwnerNotification.Status.PENDING) {
            auditFinal(saved);
        }
        return saved;
    }

    private void auditFinal(OwnerNotification notification) {
        auditRecorder.record(BusinessAuditEvents.ownerNotified(notification.tenantId(), notification.id(),
                notification.kind().name(), notification.status().name(), notification.reason()));
    }
}
