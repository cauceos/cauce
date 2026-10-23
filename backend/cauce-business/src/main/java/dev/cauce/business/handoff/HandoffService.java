package dev.cauce.business.handoff;

import dev.cauce.business.audit.BusinessAuditEvents;
import dev.cauce.business.owner.OwnerMessages;
import dev.cauce.business.owner.OwnerNotification;
import dev.cauce.business.owner.OwnerNotificationService;
import dev.cauce.business.profile.BusinessProfileMapper;
import dev.cauce.business.profile.BusinessProfileRepository;
import dev.cauce.core.audit.AuditEventRecorder;
import dev.cauce.core.business.booking.Booking;
import dev.cauce.core.business.handoff.Handoff;
import dev.cauce.core.business.handoff.HandoffNotFoundException;
import dev.cauce.core.business.handoff.HandoffSink;
import dev.cauce.core.business.handoff.InvalidHandoffCategoryException;
import dev.cauce.core.business.handoff.UnquotedCustomerMessageException;
import dev.cauce.core.business.profile.BusinessProfile;
import dev.cauce.core.message.MessageRole;
import dev.cauce.memory.message.MessageRepository;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handoffs to the owner (ADR 0007, B7): the native adapter of the {@link HandoffSink} port, and the
 * owner's list and resolution. Opening a handoff records it, queues the owner's notice and audits
 * {@code conduct.human.escalation}, all in one transaction. The conversation is not touched: it
 * stays OPEN and the agent keeps talking to the person. Tenant-scoped under RLS.
 *
 * <p>A customer message is accepted only if it is the person's own words: it must appear, ignoring
 * case and spacing, in one of their messages in the conversation. The owner
 * reads it labelled as the customer's, so it is never the agent's paraphrase.
 */
@Service
public class HandoffService implements HandoffSink {

    private static final Pattern SPACES = Pattern.compile("\\s+");

    private final HandoffRepository repository;
    private final HandoffMapper mapper;
    private final BusinessProfileRepository profileRepository;
    private final BusinessProfileMapper profileMapper;
    private final OwnerNotificationService notifications;
    private final AuditEventRecorder auditRecorder;
    private final MessageRepository messageRepository;

    public HandoffService(HandoffRepository repository, HandoffMapper mapper,
                          BusinessProfileRepository profileRepository, BusinessProfileMapper profileMapper,
                          OwnerNotificationService notifications, AuditEventRecorder auditRecorder,
                          MessageRepository messageRepository) {
        this.repository = repository;
        this.mapper = mapper;
        this.profileRepository = profileRepository;
        this.profileMapper = profileMapper;
        this.notifications = notifications;
        this.auditRecorder = auditRecorder;
        this.messageRepository = messageRepository;
    }

    @Override
    @Transactional
    public HandoffReceipt open(Booking.Requester requester, Handoff.Request request) {
        Optional<BusinessProfile> profile = profileRepository.findByTenantId(requester.tenantId())
                .map(profileMapper::toDomain);
        if (profile.isPresent() && !profile.get().acceptsHandoffCategory(request.category())) {
            throw new InvalidHandoffCategoryException("'" + request.category() + "' is not a category of this "
                    + "business; use one of " + String.join(", ", profile.get().handoffCategories()));
        }
        if (request.customerMessage() != null) {
            requireQuoted(requester.conversationId(), request.customerMessage());
        }
        Handoff handoff = Handoff.open(requester, request);
        while (repository.existsByTenantIdAndCode(handoff.tenantId(), handoff.code())) {
            handoff = Handoff.open(requester, request); // the rare code collision: a fresh code
        }
        Handoff saved = mapper.toDomain(repository.save(mapper.toEntity(handoff)));
        String locale = profile.map(BusinessProfile::locale).orElse(null);
        OwnerNotification notice = notifications.queue(saved.tenantId(), OwnerNotification.Kind.HANDOFF, saved.id(),
                OwnerMessages.handoff(locale, saved));
        boolean ownerNotified = notice.status() == OwnerNotification.Status.PENDING;
        auditRecorder.record(BusinessAuditEvents.humanEscalation(saved, ownerNotified));
        return new HandoffReceipt(saved, ownerNotified);
    }

    private void requireQuoted(UUID conversationId, String quote) {
        String words = normalized(quote);
        boolean theirs = !words.isEmpty() && messageRepository
                .findByConversationIdOrderBySeqAsc(conversationId).stream()
                .filter(message -> message.getRole() == MessageRole.USER)
                .anyMatch(message -> normalized(message.getContent()).contains(words));
        if (!theirs) {
            throw new UnquotedCustomerMessageException("customer_message must be copied word for word from one of "
                    + "the person's messages in this conversation; otherwise leave it out");
        }
    }

    private static String normalized(String text) {
        return SPACES.matcher(text).replaceAll(" ").strip().toLowerCase(Locale.ROOT);
    }

    /**
     * Up to {@code maxRows} handoffs of the business {@code tenantId}, oldest first, only those in
     * {@code status} when non-null, after {@code afterId} when non-null (keyset pagination).
     */
    @Transactional(readOnly = true)
    public List<Handoff> listForTenant(UUID tenantId, Handoff.Status status, UUID afterId, int maxRows) {
        List<HandoffEntity> page;
        if (status == null) {
            page = afterId == null
                    ? repository.findByTenantIdOrderByIdAsc(tenantId, Limit.of(maxRows))
                    : repository.findByTenantIdAndIdGreaterThanOrderByIdAsc(tenantId, afterId, Limit.of(maxRows));
        } else {
            page = afterId == null
                    ? repository.findByTenantIdAndStatusOrderByIdAsc(tenantId, status, Limit.of(maxRows))
                    : repository.findByTenantIdAndStatusAndIdGreaterThanOrderByIdAsc(tenantId, status, afterId,
                    Limit.of(maxRows));
        }
        return page.stream().map(mapper::toDomain).toList();
    }

    /** The OPEN handoffs of the business, oldest first: what the digest lists. */
    @Transactional(readOnly = true)
    public List<Handoff> openFor(UUID tenantId) {
        return repository.findByTenantIdAndStatusOrderByCreatedAtAsc(tenantId, Handoff.Status.OPEN).stream()
                .map(mapper::toDomain).toList();
    }

    /**
     * Marks handoff {@code handoffId} resolved; resolving a resolved one changes nothing.
     *
     * @throws HandoffNotFoundException if it is not visible
     */
    @Transactional
    public Handoff resolve(UUID handoffId) {
        Handoff handoff = repository.findById(handoffId).map(mapper::toDomain).orElseThrow(() ->
                new HandoffNotFoundException("No handoff found for id " + handoffId));
        return mapper.toDomain(repository.save(mapper.toEntity(handoff.resolved())));
    }
}
