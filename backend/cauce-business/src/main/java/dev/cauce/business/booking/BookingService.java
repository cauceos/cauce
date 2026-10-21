package dev.cauce.business.booking;

import dev.cauce.business.profile.BusinessProfileRepository;
import dev.cauce.core.business.booking.Agenda;
import dev.cauce.core.business.booking.Agenda.Shift;
import dev.cauce.core.business.booking.AgendaNotFoundException;
import dev.cauce.core.business.booking.Booking;
import dev.cauce.core.business.booking.Booking.Details;
import dev.cauce.core.business.booking.Booking.Requester;
import dev.cauce.core.business.booking.BookingProvider;
import dev.cauce.core.business.booking.BookingRefusal;
import dev.cauce.core.business.booking.BookingRefusal.Reason;
import dev.cauce.core.business.booking.BookingRefusedException;
import dev.cauce.core.business.booking.DayAvailability;
import dev.cauce.core.business.booking.DayAvailability.ShiftAvailability;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The native adapter of the {@link BookingProvider} port (ADR 0007, B6), and the owner's view of
 * the bookings. Tenant-scoped under RLS.
 *
 * <p>Every write locks the business's agenda row first and counts capacity after the lock, so
 * concurrent proposals for the last places of a shift are serialized and never overbook. Local
 * dates and shift times are in the business profile's time zone (UTC without a profile).
 */
@Service
public class BookingService implements BookingProvider {

    private static final Set<Booking.Status> LIVE = EnumSet.of(Booking.Status.HELD, Booking.Status.CONFIRMED);

    private final AgendaRepository agendaRepository;
    private final AgendaMapper agendaMapper;
    private final BookingRepository bookingRepository;
    private final BookingMapper bookingMapper;
    private final BusinessProfileRepository profileRepository;
    private final Clock clock;

    public BookingService(AgendaRepository agendaRepository, AgendaMapper agendaMapper,
                          BookingRepository bookingRepository, BookingMapper bookingMapper,
                          BusinessProfileRepository profileRepository, Clock clock) {
        this.agendaRepository = agendaRepository;
        this.agendaMapper = agendaMapper;
        this.bookingRepository = bookingRepository;
        this.bookingMapper = bookingMapper;
        this.profileRepository = profileRepository;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Agenda> findAgenda(UUID tenantId) {
        return agendaRepository.findByTenantId(tenantId).map(agendaMapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public DayAvailability availability(UUID tenantId, LocalDate date) {
        Agenda agenda = findAgenda(tenantId).orElseThrow(() -> noAgenda(tenantId));
        Instant now = clock.instant();
        ZoneId zone = zoneOf(tenantId);
        List<ShiftAvailability> shifts = new ArrayList<>();
        for (Shift shift : agenda.shiftsOn(date)) {
            int remaining = Math.max(0, shift.capacity() - used(tenantId, date, shift, now, Set.of()));
            boolean bookable = remaining > 0 && agenda.refusalFor(date, shift, 1, now, zone).isEmpty();
            shifts.add(new ShiftAvailability(shift.id(), shift.start(), shift.end(), shift.capacity(), remaining,
                    bookable));
        }
        return new DayAvailability(date, agenda.isClosedOn(date), shifts);
    }

    @Override
    @Transactional
    public Booking propose(Requester requester, Details details) {
        Agenda agenda = lockAgenda(requester.tenantId());
        Instant now = clock.instant();
        Shift shift = requireShift(agenda, details.shiftId());
        Details canonical = new Details(details.date(), shift.id(), details.partySize(), details.contactName(),
                details.contactPhone(), details.notes());
        requireBookable(agenda, requester.tenantId(), canonical, shift, now, Set.of());
        return save(Booking.propose(requester, canonical, holdUntil(agenda, now), null));
    }

    @Override
    @Transactional
    public Booking proposeChange(Requester requester, String code, Change change) {
        Agenda agenda = lockAgenda(requester.tenantId());
        Instant now = clock.instant();
        Booking original = requireOwn(requester, code);
        if (original.status() != Booking.Status.CONFIRMED) {
            throw refusal(Reason.NOT_PENDING, "only a confirmed booking can be changed; " + original.code()
                    + " is " + original.status());
        }
        Shift shift = requireShift(agenda, change.shiftId() == null ? original.shiftId() : change.shiftId());
        Details details = new Details(change.date() == null ? original.date() : change.date(), shift.id(),
                change.partySize() == null ? original.partySize() : change.partySize(), original.contactName(),
                original.contactPhone().orElse(null),
                change.notes() == null ? original.notes().orElse(null) : change.notes());
        requireBookable(agenda, requester.tenantId(), details, shift, now, Set.of(original.id()));
        return save(Booking.propose(requester, details, holdUntil(agenda, now), original.id()));
    }

    @Override
    @Transactional
    public Booking proposeCancellation(Requester requester, String code) {
        Agenda agenda = lockAgenda(requester.tenantId());
        Instant now = clock.instant();
        Booking booking = requireOwn(requester, code);
        if (booking.status() != Booking.Status.CONFIRMED) {
            throw refusal(Reason.NOT_PENDING, "only a confirmed booking can be cancelled; " + booking.code()
                    + " is " + booking.status());
        }
        Shift shift = agenda.shift(booking.shiftId()).orElse(null);
        if (shift != null && !Agenda.startOf(booking.date(), shift, zoneOf(requester.tenantId())).isAfter(now)) {
            throw refusal(Reason.PAST, booking.code() + " has already started");
        }
        return save(booking.withCancellationRequested(requester.messageId(), holdUntil(agenda, now)));
    }

    @Override
    @Transactional
    public Booking confirm(Requester requester, String code) {
        Agenda agenda = lockAgenda(requester.tenantId());
        Instant now = clock.instant();
        Booking booking = requireOwn(requester, code);
        Optional<BookingRefusal> refused = booking.confirmationRefusal(requester.messageId(), now);
        if (refused.isPresent()) {
            throw refused.get().toException();
        }
        if (booking.status() == Booking.Status.CONFIRMED) {
            return save(booking.cancelled()); // the pending change is a cancellation
        }
        Shift shift = requireShift(agenda, booking.shiftId());
        Set<UUID> excluded = new HashSet<>(Set.of(booking.id()));
        booking.replacesBookingId().ifPresent(excluded::add);
        if (used(requester.tenantId(), booking.date(), shift, now, excluded) + booking.partySize() > shift.capacity()) {
            throw refusal(Reason.FULL, "the places held for " + booking.code() + " are no longer available");
        }
        Booking confirmed = save(booking.confirmed());
        booking.replacesBookingId()
                .flatMap(bookingRepository::findById)
                .map(bookingMapper::toDomain)
                .filter(replaced -> replaced.status() == Booking.Status.CONFIRMED)
                .ifPresent(replaced -> save(replaced.cancelled()));
        return confirmed;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Booking> upcomingFor(UUID tenantId, UUID identityId) {
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, zoneOf(tenantId));
        return bookingRepository.findByTenantIdAndIdentityIdAndServiceDateGreaterThanEqualAndStatusIn(
                        tenantId, identityId, today, LIVE).stream()
                .map(bookingMapper::toDomain)
                .filter(booking -> booking.status() == Booking.Status.CONFIRMED || booking.takesCapacityAt(now))
                .sorted(Comparator.comparing(Booking::date).thenComparing(Booking::createdAt))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Booking> confirmedOn(UUID tenantId, LocalDate date) {
        Optional<Agenda> agenda = findAgenda(tenantId);
        Comparator<Booking> byStart = Comparator.comparing(booking -> agenda
                .flatMap(a -> a.shift(booking.shiftId())).map(Shift::start).orElse(LocalTime.MAX));
        return bookingRepository.findByTenantIdAndServiceDateAndStatus(tenantId, date, Booking.Status.CONFIRMED)
                .stream().map(bookingMapper::toDomain)
                .sorted(byStart.thenComparing(Booking::createdAt))
                .toList();
    }

    /**
     * The owner's view: up to {@code maxRows} bookings of the business in every status, optionally
     * of one {@code date}, after {@code afterId} when non-null (keyset pagination on the id).
     */
    @Transactional(readOnly = true)
    public List<Booking> listForTenant(UUID tenantId, LocalDate date, UUID afterId, int maxRows) {
        List<BookingEntity> page;
        if (date == null) {
            page = afterId == null
                    ? bookingRepository.findByTenantIdOrderByIdAsc(tenantId, Limit.of(maxRows))
                    : bookingRepository.findByTenantIdAndIdGreaterThanOrderByIdAsc(tenantId, afterId, Limit.of(maxRows));
        } else {
            page = afterId == null
                    ? bookingRepository.findByTenantIdAndServiceDateOrderByIdAsc(tenantId, date, Limit.of(maxRows))
                    : bookingRepository.findByTenantIdAndServiceDateAndIdGreaterThanOrderByIdAsc(tenantId, date,
                    afterId, Limit.of(maxRows));
        }
        return page.stream().map(bookingMapper::toDomain).toList();
    }

    /** The time zone the business's dates and shifts are in: its profile's, UTC without one. */
    public ZoneId zoneOf(UUID tenantId) {
        return profileRepository.findByTenantId(tenantId)
                .map(profile -> ZoneId.of(profile.getTimeZone()))
                .orElse(ZoneOffset.UTC);
    }

    private void requireBookable(Agenda agenda, UUID tenantId, Details details, Shift shift, Instant now,
                                 Set<UUID> excluded) {
        Optional<BookingRefusal> refused = agenda.refusalFor(details.date(), shift, details.partySize(), now,
                zoneOf(tenantId));
        if (refused.isPresent()) {
            throw refused.get().toException();
        }
        if (agenda.requirePhone() && details.contactPhone() == null) {
            throw refusal(Reason.INVALID, "a contact phone is required: ask the person for one");
        }
        int remaining = shift.capacity() - used(tenantId, details.date(), shift, now, excluded);
        if (details.partySize() > remaining) {
            throw refusal(Reason.FULL, "'" + shift.id() + "' on " + details.date() + " has " + Math.max(0, remaining)
                    + " " + agenda.unit() + " left");
        }
    }

    /** The units of {@code shift} on {@code date} taken at {@code now}, leaving out {@code excluded}. */
    private int used(UUID tenantId, LocalDate date, Shift shift, Instant now, Set<UUID> excluded) {
        return bookingRepository.findByTenantIdAndServiceDateAndShiftIdAndStatusIn(tenantId, date, shift.id(), LIVE)
                .stream()
                .map(bookingMapper::toDomain)
                .filter(booking -> !excluded.contains(booking.id()) && booking.takesCapacityAt(now))
                .mapToInt(Booking::partySize)
                .sum();
    }

    private Agenda lockAgenda(UUID tenantId) {
        return agendaRepository.lockByTenantId(tenantId).map(agendaMapper::toDomain)
                .orElseThrow(() -> noAgenda(tenantId));
    }

    private static Shift requireShift(Agenda agenda, String shiftId) {
        return agenda.shift(shiftId).orElseThrow(() -> refusal(Reason.INVALID, "there is no shift '" + shiftId
                + "'; the shifts are " + agenda.shifts().stream().map(Shift::id).collect(Collectors.joining(", "))));
    }

    /** The requester's own booking {@code code}: someone else's is, to them, a booking that does not exist. */
    private Booking requireOwn(Requester requester, String code) {
        String normalized = code == null ? "" : code.strip().toUpperCase(Locale.ROOT);
        return bookingRepository.findByTenantIdAndCode(requester.tenantId(), normalized)
                .map(bookingMapper::toDomain)
                .filter(booking -> booking.identityId().equals(requester.identityId()))
                .orElseThrow(() -> refusal(Reason.NOT_FOUND, "this person has no booking " + normalized));
    }

    /** Saves a new or changed booking; a new one gets a fresh code on the rare collision. */
    private Booking save(Booking booking) {
        Booking toSave = booking;
        while (toSave.status() == Booking.Status.HELD && bookingRepository.findById(toSave.id()).isEmpty()
                && bookingRepository.existsByTenantIdAndCode(toSave.tenantId(), toSave.code())) {
            toSave = Booking.rehydrate(toSave.id(), toSave.tenantId(), Booking.newCode(), toSave.agentId(),
                    toSave.conversationId(), toSave.identityId(), toSave.date(), toSave.shiftId(), toSave.partySize(),
                    toSave.contactName(), toSave.contactPhone().orElse(null), toSave.notes().orElse(null),
                    toSave.status(), toSave.replacesBookingId().orElse(null), toSave.proposedInMessageId(),
                    toSave.holdExpiresAt(), null, null, toSave.createdAt(), toSave.updatedAt());
        }
        return bookingMapper.toDomain(bookingRepository.save(bookingMapper.toEntity(toSave)));
    }

    private static Instant holdUntil(Agenda agenda, Instant now) {
        return now.plus(Duration.ofMinutes(agenda.holdMinutes())).truncatedTo(ChronoUnit.MICROS);
    }

    private static BookingRefusedException refusal(Reason reason, String message) {
        return new BookingRefusal(reason, message).toException();
    }

    private static AgendaNotFoundException noAgenda(UUID tenantId) {
        return new AgendaNotFoundException("No agenda found for tenant " + tenantId);
    }
}
