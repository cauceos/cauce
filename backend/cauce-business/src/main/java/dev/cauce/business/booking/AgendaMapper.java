package dev.cauce.business.booking;

import dev.cauce.core.business.booking.Agenda;
import dev.cauce.core.business.booking.Agenda.Settings;
import dev.cauce.core.business.booking.Agenda.Shift;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Hand-written mapper between {@link Agenda} and {@link AgendaEntity}. A shift is stored as
 * {id, days, start, end, capacity}, its days as the three-letter English abbreviations the API
 * uses ({@code mon} … {@code sun}) and its times as {@code HH:mm}.
 */
@Component
public class AgendaMapper {

    public AgendaEntity toEntity(Agenda agenda) {
        return new AgendaEntity(agenda.id(), agenda.tenantId(), agenda.unit(), agenda.maxPartySize(),
                agenda.holdMinutes(), agenda.horizonDays(), agenda.requirePhone(),
                agenda.shifts().stream().map(AgendaMapper::toMap).toList(),
                agenda.closedDates().toArray(LocalDate[]::new), agenda.createdAt(), agenda.updatedAt());
    }

    public Agenda toDomain(AgendaEntity entity) {
        List<Shift> shifts = entity.getShifts().stream().map(AgendaMapper::toShift).toList();
        return Agenda.rehydrate(entity.getId(), entity.getTenantId(), new Settings(entity.getUnit(),
                        entity.getMaxPartySize(), entity.getHoldMinutes(), entity.getHorizonDays(),
                        entity.isRequirePhone(), shifts, Set.of(entity.getClosedDates())),
                entity.getCreatedAt(), entity.getUpdatedAt());
    }

    /** The stored (and hashed) shape of a shift. */
    public static Map<String, Object> toMap(Shift shift) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", shift.id());
        map.put("days", EnumSet.copyOf(shift.days()).stream().map(AgendaMapper::dayCode).toList());
        map.put("start", shift.start().toString());
        map.put("end", shift.end().toString());
        map.put("capacity", shift.capacity());
        return map;
    }

    @SuppressWarnings("unchecked")
    private static Shift toShift(Map<String, Object> map) {
        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        ((List<String>) map.get("days")).forEach(day -> days.add(parseDay(day)));
        return new Shift((String) map.get("id"), days, LocalTime.parse((String) map.get("start")),
                LocalTime.parse((String) map.get("end")), ((Number) map.get("capacity")).intValue());
    }

    /** {@code mon} … {@code sun}. */
    public static String dayCode(DayOfWeek day) {
        return day.name().substring(0, 3).toLowerCase(Locale.ROOT);
    }

    /**
     * Parses a day code ({@code mon} … {@code sun}), case-insensitively.
     *
     * @throws IllegalArgumentException if it is not one
     */
    public static DayOfWeek parseDay(String code) {
        String wanted = code == null ? "" : code.strip().toLowerCase(Locale.ROOT);
        for (DayOfWeek day : DayOfWeek.values()) {
            if (dayCode(day).equals(wanted)) {
                return day;
            }
        }
        throw new IllegalArgumentException("'" + code + "' is not a day: use mon, tue, wed, thu, fri, sat or sun");
    }
}
