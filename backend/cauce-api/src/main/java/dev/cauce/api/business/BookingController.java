package dev.cauce.api.business;

import dev.cauce.api.web.PageParams;
import dev.cauce.api.web.PageResponse;
import dev.cauce.business.booking.AgendaMapper;
import dev.cauce.business.booking.AgendaService;
import dev.cauce.business.booking.BookingService;
import dev.cauce.core.business.booking.Agenda;
import dev.cauce.core.business.booking.AgendaNotFoundException;
import jakarta.validation.Valid;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The agenda of a business and the owner's view of its bookings (ADR 0007, B6). Bookings are made
 * by agents through their tools, never over this API. A business is a CLIENT tenant; one outside
 * the caller's scope is a 404.
 */
@RestController
public class BookingController {

    private final AgendaService agendaService;
    private final BookingService bookingService;

    public BookingController(AgendaService agendaService, BookingService bookingService) {
        this.agendaService = agendaService;
        this.bookingService = bookingService;
    }

    @PutMapping("/v1/tenants/{tenantId}/agenda")
    public AgendaResponse putAgenda(@PathVariable UUID tenantId, @Valid @RequestBody AgendaRequest request) {
        List<Agenda.Shift> shifts = request.shifts().stream().map(BookingController::toShift).toList();
        Agenda.Settings settings = new Agenda.Settings(request.unit(), request.maxPartySize(),
                request.holdMinutes() == null ? AgendaRequest.DEFAULT_HOLD_MINUTES : request.holdMinutes(),
                request.horizonDays() == null ? AgendaRequest.DEFAULT_HORIZON_DAYS : request.horizonDays(),
                Boolean.TRUE.equals(request.requirePhone()), shifts,
                request.closedDates() == null ? Set.of() : Set.copyOf(request.closedDates()));
        return AgendaResponse.from(agendaService.putAgenda(tenantId, settings));
    }

    @GetMapping("/v1/tenants/{tenantId}/agenda")
    public AgendaResponse getAgenda(@PathVariable UUID tenantId) {
        return AgendaResponse.from(bookingService.findAgenda(tenantId).orElseThrow(() ->
                new AgendaNotFoundException("No agenda found for tenant " + tenantId)));
    }

    /** Every booking of the business in any status, optionally of one {@code date}; keyset-paginated. */
    @GetMapping("/v1/tenants/{tenantId}/bookings")
    public PageResponse<BookingResponse> listBookings(
            @PathVariable UUID tenantId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String cursor) {
        PageParams page = PageParams.parse(limit, cursor);
        List<BookingResponse> fetched = bookingService
                .listForTenant(tenantId, date, page.afterId(), page.fetchSize())
                .stream().map(BookingResponse::from).toList();
        return PageResponse.of(fetched, page.limit(), BookingResponse::id);
    }

    private static Agenda.Shift toShift(AgendaRequest.Shift shift) {
        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        shift.days().forEach(day -> days.add(AgendaMapper.parseDay(day)));
        return new Agenda.Shift(shift.id(), days, shift.start(), shift.end(), shift.capacity());
    }
}
