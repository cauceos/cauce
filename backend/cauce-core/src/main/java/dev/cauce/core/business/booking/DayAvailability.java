package dev.cauce.core.business.booking;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Objects;

/**
 * What remains to book on one date (ADR 0007, B6): each shift that runs that day with its capacity
 * and what is left, and whether it can still be booked (it has not started, it is within the
 * horizon). A closed date has no shifts.
 */
public record DayAvailability(LocalDate date, boolean closed, List<ShiftAvailability> shifts) {

    /** One shift on the date: {@code remaining} units of {@code capacity}, bookable or not. */
    public record ShiftAvailability(String shiftId, LocalTime start, LocalTime end, int capacity, int remaining,
                                    boolean bookable) {
    }

    public DayAvailability {
        Objects.requireNonNull(date, "date");
        shifts = List.copyOf(shifts);
    }
}
