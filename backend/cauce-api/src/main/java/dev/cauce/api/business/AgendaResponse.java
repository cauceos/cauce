package dev.cauce.api.business;

import dev.cauce.business.booking.AgendaMapper;
import dev.cauce.core.business.booking.Agenda;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** An agenda over the wire; shifts in the request's shape (days as {@code mon} … {@code sun}). */
public record AgendaResponse(
        UUID id,
        UUID tenantId,
        String unit,
        int maxPartySize,
        int holdMinutes,
        int horizonDays,
        boolean requirePhone,
        List<Map<String, Object>> shifts,
        List<LocalDate> closedDates,
        Instant createdAt,
        Instant updatedAt) {

    public static AgendaResponse from(Agenda agenda) {
        return new AgendaResponse(agenda.id(), agenda.tenantId(), agenda.unit(), agenda.maxPartySize(),
                agenda.holdMinutes(), agenda.horizonDays(), agenda.requirePhone(),
                agenda.shifts().stream().map(AgendaMapper::toMap).toList(), List.copyOf(agenda.closedDates()),
                agenda.createdAt(), agenda.updatedAt());
    }
}
