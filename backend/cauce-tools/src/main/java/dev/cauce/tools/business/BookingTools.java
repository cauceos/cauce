package dev.cauce.tools.business;

import dev.cauce.core.audit.CanonicalJson;
import dev.cauce.core.business.booking.Agenda;
import dev.cauce.core.business.booking.AgendaNotFoundException;
import dev.cauce.core.business.booking.Booking;
import dev.cauce.core.business.booking.BookingProvider;
import dev.cauce.core.business.booking.BookingProvider.Change;
import dev.cauce.core.business.booking.BookingRefusedException;
import dev.cauce.core.business.booking.DayAvailability;
import dev.cauce.core.tool.ToolCall;
import dev.cauce.core.tool.ToolDefinition;
import dev.cauce.core.tool.ToolExecutionContext;
import dev.cauce.core.tool.ToolResult;
import dev.cauce.tools.spi.Tool;
import dev.cauce.tools.spi.ToolArguments;
import dev.cauce.tools.spi.ToolArguments.InvalidToolArgumentException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The booking tools (ADR 0007, B6): sector-neutral, over the {@link BookingProvider} port. Every
 * write is a proposal that holds capacity; {@code confirm_booking} applies it, and the port refuses
 * it from the turn it was proposed in. Whose booking it is comes from the execution context, never
 * from the arguments (B3). A refusal is an answer, not an error: it comes back as
 * {@code {"ok": false, "reason", "message"}} for the agent to explain and offer alternatives.
 */
public final class BookingTools {

    static final String NEXT_STEP = "Read these details back to the person and ask them to confirm. Call "
            + "confirm_booking with the code only after they reply that they agree, never in this same turn.";

    private BookingTools() {
    }

    /** {@code check_availability}: what remains on a date, per shift. */
    public static Tool checkAvailability(Supplier<BookingProvider> provider) {
        return new BookingTool(provider, new ToolDefinition("check_availability",
                "Returns, for one date, each shift the business takes bookings for, with its start and end time, "
                        + "the places left and whether it can still be booked. A closed date has no shifts. Use the "
                        + "local date of the business (get_current_time gives today's).",
                schema(Map.of("date", dateProperty()), List.of("date")))) {
            @Override
            public boolean lookup() {
                return true;
            }

            @Override
            Map<String, Object> run(BookingProvider bookings, ToolArguments args, ToolExecutionContext context) {
                LocalDate date = date(args, "date");
                Agenda agenda = agenda(bookings, context);
                DayAvailability day = bookings.availability(context.tenantId(), date);
                Map<String, Object> out = ok();
                out.put("date", date.toString());
                out.put("weekday", weekday(date));
                out.put("closed", day.closed() || day.shifts().isEmpty());
                out.put("unit", agenda.unit());
                out.put("max_party_size", agenda.maxPartySize());
                out.put("shifts", day.shifts().stream().map(shift -> Map.of(
                        "shift", shift.shiftId(),
                        "start", shift.start().toString(),
                        "end", shift.end().toString(),
                        "remaining", shift.remaining(),
                        "bookable", shift.bookable())).toList());
                return out;
            }
        };
    }

    /** {@code propose_booking}: holds a new booking until the person confirms it. */
    public static Tool proposeBooking(Supplier<BookingProvider> provider, Clock clock) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("date", dateProperty());
        properties.put("shift", text("The shift, as check_availability names it."));
        properties.put("time", timeProperty("The time the person asked for, if they named one; it must fall "
                + "within the shift. Places are counted per shift, the time is kept and read back."));
        properties.put("party_size", Map.of("type", "integer", "minimum", 1,
                "description", "How many people (or units) the booking is for."));
        properties.put("contact_name", text("The name the booking is under."));
        properties.put("contact_phone", text("A phone number to reach the person, if they gave one."));
        properties.put("notes", text("Anything the person asked for: a high chair, the terrace."));
        return new BookingTool(provider, new ToolDefinition("propose_booking",
                "Proposes a booking and holds the places for a few minutes. It is NOT confirmed: read the details "
                        + "back and ask the person to confirm, then call confirm_booking in a later turn. Parties "
                        + "above the maximum are refused: hand them to the owner instead.",
                schema(properties, List.of("date", "shift", "party_size", "contact_name")))) {
            @Override
            Map<String, Object> run(BookingProvider bookings, ToolArguments args, ToolExecutionContext context) {
                Booking.Details details = details(args);
                Booking booking = bookings.propose(requester(context), details);
                return proposed(bookings, context, booking, clock.instant());
            }
        };
    }

    /** {@code propose_booking_change}: proposes a change of one of the person's confirmed bookings. */
    public static Tool proposeBookingChange(Supplier<BookingProvider> provider, Clock clock) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("code", text("The code of the booking to change."));
        properties.put("date", dateProperty());
        properties.put("shift", text("The new shift, if it changes."));
        properties.put("time", timeProperty("The new time, if it changes. Moving to another shift without a "
                + "new time leaves the booking with no time."));
        properties.put("party_size", Map.of("type", "integer", "minimum", 1,
                "description", "The new party size, if it changes."));
        properties.put("notes", text("The new notes, if they change."));
        return new BookingTool(provider, new ToolDefinition("propose_booking_change",
                "Proposes a change to one of this person's confirmed bookings (date, shift, time, party size or "
                        + "notes; leave out what does not change). The change is held, not applied: ask the person "
                        + "to confirm and call confirm_booking with the same code in a later turn. The booking keeps "
                        + "its code: never tell the person it changed. A new proposal withdraws the previous one.",
                schema(properties, List.of("code")))) {
            @Override
            Map<String, Object> run(BookingProvider bookings, ToolArguments args, ToolExecutionContext context) {
                LocalDate date = args.text("date").isPresent() ? date(args, "date") : null;
                Change change = new Change(date, args.text("shift").orElse(null), time(args).orElse(null),
                        args.integer("party_size").orElse(null), args.text("notes").orElse(null));
                Booking booking = bookings.proposeChange(requester(context), args.requiredText("code"), change);
                return proposed(bookings, context, booking, clock.instant());
            }
        };
    }

    /** {@code propose_cancellation}: requests the cancellation of one of the person's bookings. */
    public static Tool proposeCancellation(Supplier<BookingProvider> provider, Clock clock) {
        return new BookingTool(provider, new ToolDefinition("propose_cancellation",
                "Requests the cancellation of one of this person's confirmed bookings. It is NOT cancelled yet: "
                        + "ask the person to confirm and call confirm_booking with the code in a later turn.",
                schema(Map.of("code", text("The code of the booking to cancel.")), List.of("code")))) {
            @Override
            Map<String, Object> run(BookingProvider bookings, ToolArguments args, ToolExecutionContext context) {
                Booking booking = bookings.proposeCancellation(requester(context), args.requiredText("code"));
                Map<String, Object> out = ok();
                out.put("booking", view(bookings, context, booking, clock.instant()));
                out.put("next_step", "Ask the person to confirm the cancellation. Call confirm_booking with the code "
                        + "only after they reply that they agree, never in this same turn.");
                return out;
            }
        };
    }

    /** {@code confirm_booking}: applies what a booking waits for, after the person agreed. */
    public static Tool confirmBooking(Supplier<BookingProvider> provider, Clock clock) {
        return new BookingTool(provider, new ToolDefinition("confirm_booking",
                "Confirms what a booking of this person is waiting for (a new booking, a change or a cancellation). "
                        + "Call it only after the person has replied that they agree with the details you read "
                        + "back. It is refused in the same turn the proposal was made.",
                schema(Map.of("code", text("The code of the booking.")), List.of("code")))) {
            @Override
            Map<String, Object> run(BookingProvider bookings, ToolArguments args, ToolExecutionContext context) {
                Booking booking = bookings.confirm(requester(context), args.requiredText("code"));
                Map<String, Object> out = ok();
                out.put("booking", view(bookings, context, booking, clock.instant()));
                return out;
            }
        };
    }

    /** {@code list_my_bookings}: the person's upcoming bookings. */
    public static Tool listMyBookings(Supplier<BookingProvider> provider, Clock clock) {
        return new BookingTool(provider, new ToolDefinition("list_my_bookings",
                "Lists the upcoming bookings of the person you are talking to, with their codes: the confirmed "
                        + "ones and those still waiting for confirmation. Takes no arguments.",
                schema(Map.of(), List.of()))) {
            @Override
            Map<String, Object> run(BookingProvider bookings, ToolArguments args, ToolExecutionContext context) {
                Map<String, Object> out = ok();
                Instant now = clock.instant();
                out.put("bookings", bookings.upcomingFor(context.tenantId(), context.identityId()).stream()
                        .map(booking -> view(bookings, context, booking, now)).toList());
                return out;
            }
        };
    }

    /** The shared handling: the port lookup, refusals as answers, a missing agenda as unavailable. */
    private abstract static class BookingTool implements Tool {

        private final Supplier<BookingProvider> provider;
        private final ToolDefinition definition;

        BookingTool(Supplier<BookingProvider> provider, ToolDefinition definition) {
            this.provider = provider;
            this.definition = definition;
        }

        abstract Map<String, Object> run(BookingProvider bookings, ToolArguments args, ToolExecutionContext context);

        @Override
        public ToolDefinition definition() {
            return definition;
        }

        @Override
        public ToolResult execute(ToolCall call, ToolExecutionContext context) {
            BookingProvider bookings = provider.get();
            Map<String, Object> out;
            if (bookings == null) {
                out = unavailable();
            } else {
                try {
                    out = run(bookings, ToolArguments.of(call), context);
                } catch (BookingRefusedException e) {
                    out = new LinkedHashMap<>();
                    out.put("ok", false);
                    out.put("reason", e.refusal().reason().name());
                    out.put("message", e.refusal().message());
                } catch (AgendaNotFoundException e) {
                    out = unavailable();
                } catch (IllegalArgumentException e) {
                    // A domain rule on the arguments (a blank name, an overlong note): the model can fix it.
                    throw new InvalidToolArgumentException(e.getMessage());
                }
            }
            return ToolResult.success(call.toolCallId(), definition.name(), CanonicalJson.serialize(out));
        }
    }

    private static Map<String, Object> unavailable() {
        return Map.of("available", false, "note", "This business takes no bookings here.");
    }

    private static Map<String, Object> ok() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        return out;
    }

    private static Map<String, Object> proposed(BookingProvider bookings, ToolExecutionContext context,
                                                Booking booking, Instant now) {
        Map<String, Object> out = ok();
        out.put("booking", view(bookings, context, booking, now));
        out.put("confirm_within_minutes", agenda(bookings, context).holdMinutes());
        out.put("next_step", NEXT_STEP);
        return out;
    }

    /**
     * The booking as the agent reads it; what it still waits for is judged at {@code now}, a reading
     * of the database clock its hold was stamped with (ADR 0008).
     */
    private static Map<String, Object> view(BookingProvider bookings, ToolExecutionContext context, Booking booking,
                                            Instant now) {
        Optional<Agenda.Shift> shift = bookings.findAgenda(context.tenantId()).flatMap(a -> a.shift(booking.shiftId()));
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("code", booking.code());
        view.put("date", booking.date().toString());
        view.put("weekday", weekday(booking.date()));
        view.put("shift", booking.shiftId());
        shift.ifPresent(s -> view.put("start", s.start().toString()));
        booking.time().ifPresent(time -> view.put("time", time.toString()));
        view.put("party_size", booking.partySize());
        view.put("contact_name", booking.contactName());
        booking.contactPhone().ifPresent(phone -> view.put("contact_phone", phone));
        booking.notes().ifPresent(notes -> view.put("notes", notes));
        view.put("status", booking.status().name());
        if (booking.status() == Booking.Status.HELD && booking.replacesBookingId().isPresent()) {
            view.put("pending_change", true); // a proposed change of the confirmed booking with this same code
        }
        booking.pendingAt(now)
                .ifPresent(pending -> view.put("waiting_for", pending.name().toLowerCase(Locale.ROOT)));
        return view;
    }

    private static Agenda agenda(BookingProvider bookings, ToolExecutionContext context) {
        return bookings.findAgenda(context.tenantId())
                .orElseThrow(() -> new AgendaNotFoundException("No agenda for " + context.tenantId()));
    }

    private static Booking.Requester requester(ToolExecutionContext context) {
        return new Booking.Requester(context.tenantId(), context.agentId(), context.conversationId(),
                context.identityId(), context.triggerMessageId());
    }

    private static Booking.Details details(ToolArguments args) {
        return new Booking.Details(date(args, "date"), args.requiredText("shift"), time(args).orElse(null),
                args.requiredInteger("party_size"), args.requiredText("contact_name"),
                args.text("contact_phone").orElse(null), args.text("notes").orElse(null));
    }

    private static Optional<LocalTime> time(ToolArguments args) {
        Optional<String> text = args.text("time");
        if (text.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalTime.parse(text.get()));
        } catch (DateTimeParseException e) {
            throw new InvalidToolArgumentException("'time' must be a time as HH:MM, not '" + text.get() + "'");
        }
    }

    private static LocalDate date(ToolArguments args, String name) {
        String text = args.requiredText(name);
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            throw new InvalidToolArgumentException("'" + name + "' must be a date as YYYY-MM-DD, not '" + text + "'");
        }
    }

    private static String weekday(LocalDate date) {
        return date.getDayOfWeek().name().toLowerCase(Locale.ROOT);
    }

    private static Map<String, Object> dateProperty() {
        return Map.of("type", "string", "description", "The date as YYYY-MM-DD, in the business's local calendar.");
    }

    private static Map<String, Object> timeProperty(String description) {
        return Map.of("type", "string", "description", "As HH:MM, 24-hour, local time. " + description);
    }

    private static Map<String, Object> text(String description) {
        return Map.of("type", "string", "description", description);
    }

    private static Map<String, Object> schema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        schema.put("additionalProperties", false);
        return schema;
    }
}
