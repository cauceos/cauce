package dev.cauce.business.digest;

import dev.cauce.business.booking.BookingService;
import dev.cauce.business.gap.KnowledgeGapService;
import dev.cauce.business.handoff.HandoffService;
import dev.cauce.business.owner.OwnerNotification;
import dev.cauce.business.owner.OwnerNotificationService;
import dev.cauce.business.profile.BusinessProfileMapper;
import dev.cauce.business.profile.BusinessProfileRepository;
import dev.cauce.core.UuidGenerator;
import dev.cauce.core.business.booking.Booking;
import dev.cauce.core.business.gap.KnowledgeGap;
import dev.cauce.core.business.handoff.Handoff;
import dev.cauce.core.business.profile.BusinessProfile;
import dev.cauce.core.business.profile.BusinessProfileNotFoundException;
import dev.cauce.core.tenant.NoTenantContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The nightly summary (ADR 0007, B10). For one business and one local date it composes, by code,
 * tomorrow's confirmed bookings, the open handoffs and the questions left unanswered since the
 * previous summary; records it once per (business, date) — the insert-first claim makes every
 * re-run a no-op — and queues the owner's notice through the outbox, which applies the reply-window
 * rule. Tenant-scoped under RLS, except the scheduler's cross-tenant read of who wants a summary.
 */
@Service
public class DailyDigestService {

    /** How far back the first summary of a business looks for unanswered questions. */
    static final Duration FIRST_WINDOW = Duration.ofDays(1);

    private final DailyDigestRepository repository;
    private final BusinessProfileRepository profileRepository;
    private final BusinessProfileMapper profileMapper;
    private final BookingService bookings;
    private final HandoffService handoffs;
    private final KnowledgeGapService questions;
    private final OwnerNotificationService notifications;
    private final Clock clock;

    public DailyDigestService(DailyDigestRepository repository, BusinessProfileRepository profileRepository,
                              BusinessProfileMapper profileMapper, BookingService bookings, HandoffService handoffs,
                              KnowledgeGapService questions, OwnerNotificationService notifications,
                              @Qualifier("businessClock") Clock clock) {
        this.repository = repository;
        this.profileRepository = profileRepository;
        this.profileMapper = profileMapper;
        this.bookings = bookings;
        this.handoffs = handoffs;
        this.questions = questions;
        this.notifications = notifications;
        this.clock = clock;
    }

    /** The businesses that want a summary, across tenants (the V38 SECURITY DEFINER read). */
    @Transactional(readOnly = true)
    @NoTenantContext
    public List<DailyDigestRepository.Candidate> candidates() {
        return repository.candidates();
    }

    /**
     * The summary of the business {@code tenantId} for today, its local date: composed and queued
     * now if today has none yet, the existing one otherwise (a re-run sends nothing new).
     *
     * @throws BusinessProfileNotFoundException if the business has no profile (no time zone, no name)
     */
    @Transactional
    public DailyDigest runToday(UUID tenantId) {
        BusinessProfile profile = profileRepository.findByTenantId(tenantId).map(profileMapper::toDomain)
                .orElseThrow(() -> new BusinessProfileNotFoundException("No business profile for tenant " + tenantId));
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        LocalDate today = LocalDate.ofInstant(now, profile.timeZone());
        Optional<DailyDigestEntity> existing = repository.findByTenantIdAndLocalDate(tenantId, today);
        if (existing.isPresent()) {
            return toDomain(existing.get());
        }
        Instant from = repository.findFirstByTenantIdOrderByLocalDateDesc(tenantId)
                .map(DailyDigestEntity::getCoveredTo)
                .orElse(now.minus(FIRST_WINDOW));
        LocalDate tomorrow = today.plusDays(1);
        List<Booking> tomorrowsBookings = bookings.confirmedOn(tenantId, tomorrow);
        List<Handoff> open = handoffs.openFor(tenantId);
        List<KnowledgeGap> unanswered = questions.listRecordedBetween(tenantId, from, now);
        String content = DigestComposer.compose(new DigestComposer.Contents(profile.name(), profile.locale(), tomorrow,
                bookings.findAgenda(tenantId), tomorrowsBookings, open, unanswered));
        boolean empty = tomorrowsBookings.isEmpty() && open.isEmpty() && unanswered.isEmpty();
        boolean notify = !(empty && profile.digestSchedule().map(BusinessProfile.DigestSchedule::skipWhenEmpty).orElse(false));
        UUID id = UuidGenerator.newV7();
        int inserted = repository.insertIfAbsent(id, tenantId, today, from, now, tomorrowsBookings.size(), open.size(),
                unanswered.size(), content, notify, now);
        if (inserted == 1 && notify) {
            notifications.queue(tenantId, OwnerNotification.Kind.DAILY_DIGEST, id, content);
        }
        return repository.findByTenantIdAndLocalDate(tenantId, today).map(DailyDigestService::toDomain)
                .orElseThrow(() -> new IllegalStateException("the digest of " + today + " vanished"));
    }

    /**
     * Up to {@code maxRows} summaries of the business {@code tenantId}, oldest first, after
     * {@code afterId} when non-null (keyset pagination).
     */
    @Transactional(readOnly = true)
    public List<DailyDigest> listForTenant(UUID tenantId, UUID afterId, int maxRows) {
        List<DailyDigestEntity> page = afterId == null
                ? repository.findByTenantIdOrderByIdAsc(tenantId, Limit.of(maxRows))
                : repository.findByTenantIdAndIdGreaterThanOrderByIdAsc(tenantId, afterId, Limit.of(maxRows));
        return page.stream().map(DailyDigestService::toDomain).toList();
    }

    private static DailyDigest toDomain(DailyDigestEntity e) {
        return new DailyDigest(e.getId(), e.getTenantId(), e.getLocalDate(), e.getCoveredFrom(), e.getCoveredTo(),
                e.getBookingCount(), e.getHandoffCount(), e.getQuestionCount(), e.getContent(), e.isNotified(),
                e.getCreatedAt());
    }
}
