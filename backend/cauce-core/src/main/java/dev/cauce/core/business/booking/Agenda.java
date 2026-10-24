package dev.cauce.core.business.booking;

import dev.cauce.core.UuidGenerator;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;

/**
 * The agenda of a business (ADR 0007, B6): the shifts it takes bookings for, each with a capacity
 * in the business's own unit (covers, chairs, slots), the dates it is closed, the largest party it
 * books without a human, how long a proposal holds capacity, and how far ahead it books.
 * Sector-neutral: a bar's lunch service and a hairdresser's morning are both shifts. One per
 * business tenant (B5).
 *
 * <p>Pure domain type, immutable. A replacement keeps the id and the creation time. It decides
 * whether a booking <em>may</em> be made on a date and shift; whether capacity remains is counted
 * from the bookings by the adapter.
 */
public final class Agenda {

    public static final int MAX_SHIFTS = 20;
    public static final int MAX_CLOSED_DATES = 366;

    /**
     * A recurring shift: on {@code days}, from {@code start} to {@code end}, with {@code capacity}
     * units. {@code id} is the business's own short name for it ({@code comida}, {@code cena}).
     */
    public record Shift(String id, Set<DayOfWeek> days, LocalTime start, LocalTime end, int capacity) {

        public Shift {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("a shift id must not be blank");
            }
            id = id.strip();
            if (id.length() > 40) {
                throw new IllegalArgumentException("shift id '" + id + "' exceeds 40 characters");
            }
            if (days == null || days.isEmpty()) {
                throw new IllegalArgumentException("shift '" + id + "' must run on at least one day");
            }
            days = Set.copyOf(EnumSet.copyOf(days));
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(end, "end");
            if (!end.isAfter(start)) {
                throw new IllegalArgumentException("shift '" + id + "' must end after it starts");
            }
            if (capacity < 1) {
                throw new IllegalArgumentException("shift '" + id + "' must have a capacity of at least 1");
            }
        }

        /** Whether a booking may ask for {@code time} in this shift: from its start, before its end. */
        public boolean includes(LocalTime time) {
            return !time.isBefore(start) && time.isBefore(end);
        }
    }

    private final UUID id;
    private final UUID tenantId;
    private final String unit;
    private final int maxPartySize;
    private final int holdMinutes;
    private final int horizonDays;
    private final boolean requirePhone;
    private final List<Shift> shifts;
    private final SortedSet<LocalDate> closedDates;
    private final Instant createdAt;
    private final Instant updatedAt;

    private Agenda(UUID id, UUID tenantId, String unit, int maxPartySize, int holdMinutes, int horizonDays,
                   boolean requirePhone, List<Shift> shifts, SortedSet<LocalDate> closedDates, Instant createdAt,
                   Instant updatedAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.unit = unit;
        this.maxPartySize = maxPartySize;
        this.holdMinutes = holdMinutes;
        this.horizonDays = horizonDays;
        this.requirePhone = requirePhone;
        this.shifts = shifts;
        this.closedDates = closedDates;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /** The settings of an agenda, as the business states them. */
    public record Settings(String unit, int maxPartySize, int holdMinutes, int horizonDays, boolean requirePhone,
                           List<Shift> shifts, Set<LocalDate> closedDates) {

        public Settings {
            if (unit == null || unit.isBlank()) {
                throw new IllegalArgumentException("unit must not be blank");
            }
            unit = unit.strip();
            if (unit.length() > 40) {
                throw new IllegalArgumentException("unit must be at most 40 characters");
            }
            if (maxPartySize < 1) {
                throw new IllegalArgumentException("max_party_size must be at least 1");
            }
            if (holdMinutes < 1 || holdMinutes > 1440) {
                throw new IllegalArgumentException("hold_minutes must be within [1, 1440]");
            }
            if (horizonDays < 0 || horizonDays > 730) {
                throw new IllegalArgumentException("horizon_days must be within [0, 730]");
            }
            Objects.requireNonNull(shifts, "shifts");
            if (shifts.isEmpty() || shifts.size() > MAX_SHIFTS) {
                throw new IllegalArgumentException("an agenda has between 1 and " + MAX_SHIFTS + " shifts");
            }
            Set<String> ids = new HashSet<>();
            for (Shift shift : shifts) {
                if (!ids.add(shift.id())) {
                    throw new IllegalArgumentException("shift '" + shift.id() + "' appears twice");
                }
            }
            shifts = List.copyOf(shifts);
            closedDates = closedDates == null ? Set.of() : Set.copyOf(closedDates);
            if (closedDates.size() > MAX_CLOSED_DATES) {
                throw new IllegalArgumentException("at most " + MAX_CLOSED_DATES + " closed dates");
            }
        }
    }

    /** A new agenda for {@code tenantId}. */
    public static Agenda create(UUID tenantId, Settings settings) {
        Instant now = Instant.now();
        return of(UuidGenerator.newV7(), Objects.requireNonNull(tenantId, "tenantId"), settings, now, now);
    }

    /** This agenda with its settings replaced: same id, tenant and creation time; updated now. */
    public Agenda replacedBy(Settings settings) {
        return of(id, tenantId, settings, createdAt, Instant.now());
    }

    /** Rebuilds a persisted agenda. For the persistence layer only. */
    public static Agenda rehydrate(UUID id, UUID tenantId, Settings settings, Instant createdAt, Instant updatedAt) {
        return of(Objects.requireNonNull(id, "id"), Objects.requireNonNull(tenantId, "tenantId"), settings,
                Objects.requireNonNull(createdAt, "createdAt"), Objects.requireNonNull(updatedAt, "updatedAt"));
    }

    private static Agenda of(UUID id, UUID tenantId, Settings settings, Instant createdAt, Instant updatedAt) {
        Objects.requireNonNull(settings, "settings");
        return new Agenda(id, tenantId, settings.unit(), settings.maxPartySize(), settings.holdMinutes(),
                settings.horizonDays(), settings.requirePhone(), settings.shifts(),
                java.util.Collections.unmodifiableSortedSet(new TreeSet<>(settings.closedDates())), createdAt,
                updatedAt);
    }

    /** The settings of this agenda, as they were stated. */
    public Settings settings() {
        return new Settings(unit, maxPartySize, holdMinutes, horizonDays, requirePhone, shifts, closedDates);
    }

    /** The shift named {@code shiftId}, if the agenda has one (names compare ignoring case). */
    public Optional<Shift> shift(String shiftId) {
        if (shiftId == null) {
            return Optional.empty();
        }
        return shifts.stream().filter(shift -> shift.id().equalsIgnoreCase(shiftId.strip())).findFirst();
    }

    /** Whether the business is closed all day on {@code date}. */
    public boolean isClosedOn(LocalDate date) {
        return closedDates.contains(date);
    }

    /** The shifts that run on {@code date}: none on a closed date. */
    public List<Shift> shiftsOn(LocalDate date) {
        if (isClosedOn(date)) {
            return List.of();
        }
        return shifts.stream().filter(shift -> shift.days().contains(date.getDayOfWeek())).toList();
    }

    /** When {@code shift} starts on {@code date}, in the business's {@code zone}. */
    public static Instant startOf(LocalDate date, Shift shift, ZoneId zone) {
        return ZonedDateTime.of(date, shift.start(), zone).toInstant();
    }

    /**
     * Whether a booking of {@code partySize} for {@code shift} on {@code date} may be taken at
     * {@code now} without counting capacity: the reason it may not, or empty when it may.
     */
    public Optional<BookingRefusal> refusalFor(LocalDate date, Shift shift, int partySize, Instant now, ZoneId zone) {
        Optional<BookingRefusal> past = pastDate(date, now, zone);
        if (past.isPresent()) {
            return past;
        }
        if (partySize < 1) {
            return Optional.of(new BookingRefusal(BookingRefusal.Reason.INVALID, "the party size must be at least 1"));
        }
        if (partySize > maxPartySize) {
            return Optional.of(new BookingRefusal(BookingRefusal.Reason.PARTY_TOO_LARGE, "parties above "
                    + maxPartySize + " " + unit + " are not booked here: hand the request to the owner"));
        }
        if (!shiftsOn(date).contains(shift)) {
            return Optional.of(new BookingRefusal(BookingRefusal.Reason.CLOSED,
                    "'" + shift.id() + "' does not run on " + date + " (" + date.getDayOfWeek() + ")"));
        }
        if (!startOf(date, shift, zone).isAfter(now)) {
            return Optional.of(new BookingRefusal(BookingRefusal.Reason.PAST,
                    "'" + shift.id() + "' on " + date + " has already started"));
        }
        LocalDate today = LocalDate.ofInstant(now, zone);
        if (date.isAfter(today.plusDays(horizonDays))) {
            return Optional.of(new BookingRefusal(BookingRefusal.Reason.TOO_FAR,
                    "bookings are taken at most " + horizonDays + " days ahead"));
        }
        return Optional.empty();
    }

    /**
     * A refusal of {@code date} if it is before today in {@code zone}: {@code PAST}, with today's date
     * and weekday in the message, so that the agent works the date out again from today instead of
     * from memory or an earlier turn of a long conversation.
     */
    public static Optional<BookingRefusal> pastDate(LocalDate date, Instant now, ZoneId zone) {
        LocalDate today = LocalDate.ofInstant(now, zone);
        if (!date.isBefore(today)) {
            return Optional.empty();
        }
        return Optional.of(new BookingRefusal(BookingRefusal.Reason.PAST, date + " (" + weekday(date)
                + ") is a past date: today is " + today + " (" + weekday(today) + ") in " + zone.getId()
                + ". Work dates out from today, never from memory or an earlier turn, and ask the person "
                + "which date they mean if unsure"));
    }

    private static String weekday(LocalDate date) {
        return date.getDayOfWeek().name().toLowerCase(java.util.Locale.ROOT);
    }

    public UUID id() {
        return id;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public String unit() {
        return unit;
    }

    public int maxPartySize() {
        return maxPartySize;
    }

    public int holdMinutes() {
        return holdMinutes;
    }

    public int horizonDays() {
        return horizonDays;
    }

    public boolean requirePhone() {
        return requirePhone;
    }

    public List<Shift> shifts() {
        return shifts;
    }

    public SortedSet<LocalDate> closedDates() {
        return closedDates;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || o instanceof Agenda other && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "Agenda[id=%s, tenantId=%s, shifts=%d]".formatted(id, tenantId, shifts.size());
    }
}
