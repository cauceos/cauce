package dev.cauce.business.digest;

import dev.cauce.business.owner.OwnerMessages;
import dev.cauce.core.business.booking.Agenda;
import dev.cauce.core.business.booking.Booking;
import dev.cauce.core.business.gap.KnowledgeGap;
import dev.cauce.core.business.handoff.Handoff;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Writes the text of the nightly summary (ADR 0007, B10) from the records, by code: no model, so
 * nothing is invented and nothing costs a token. Sector-neutral: bookings are counted in the
 * agenda's own unit, cases by their category. Spanish or English, after the business's locale.
 */
public final class DigestComposer {

    /** The longest summary text: a channel message, never a report. */
    static final int MAX_LENGTH = 3800;
    private static final int MAX_SUMMARY_CHARS = 140;

    private DigestComposer() {
    }

    /** What a summary reports. */
    public record Contents(String businessName, String locale, LocalDate tomorrow, Optional<Agenda> agenda,
                           List<Booking> bookings, List<Handoff> openHandoffs, List<KnowledgeGap> questions) {
    }

    public static String compose(Contents c) {
        boolean es = OwnerMessages.spanish(c.locale());
        Locale locale = es ? Locale.forLanguageTag("es") : Locale.ENGLISH;
        String day = c.tomorrow().format(DateTimeFormatter.ofPattern(es ? "EEEE d/M/yyyy" : "EEEE, d MMM yyyy", locale));
        StringBuilder text = new StringBuilder(es
                ? "Resumen de " + c.businessName() + " para mañana, " + day
                : "Summary of " + c.businessName() + " for tomorrow, " + day);
        if (c.bookings().isEmpty() && c.openHandoffs().isEmpty() && c.questions().isEmpty()) {
            text.append("\n\n").append(es ? "Nada pendiente." : "Nothing pending.");
            return text.toString();
        }

        int units = c.bookings().stream().mapToInt(Booking::partySize).sum();
        String unit = c.agenda().map(Agenda::unit).orElse(es ? "plazas" : "places");
        text.append("\n\n").append(es ? "Reservas" : "Bookings").append(" (").append(c.bookings().size());
        if (!c.bookings().isEmpty()) {
            text.append(" · ").append(units).append(' ').append(unit);
        }
        text.append(')');
        if (c.bookings().isEmpty()) {
            text.append("\n· ").append(es ? "ninguna" : "none");
        }
        for (Booking b : c.bookings()) {
            // The time the person asked for; without one, when the shift starts.
            String start = b.time().or(() -> c.agenda().flatMap(a -> a.shift(b.shiftId())).map(Agenda.Shift::start))
                    .map(LocalTime::toString).map(time -> " " + time).orElse("");
            text.append("\n· ").append(b.shiftId()).append(start).append(" — ").append(b.partySize()).append(" — ")
                    .append(b.contactName());
            b.contactPhone().ifPresent(phone -> text.append(" (").append(phone).append(')'));
            text.append(" · ref ").append(b.code());
            b.notes().ifPresent(notes -> text.append(" · ").append(shorten(notes)));
        }

        text.append("\n\n").append(es ? "Casos pendientes" : "Open cases").append(" (")
                .append(c.openHandoffs().size()).append(')');
        if (c.openHandoffs().isEmpty()) {
            text.append("\n· ").append(es ? "ninguno" : "none");
        }
        for (Handoff h : c.openHandoffs()) {
            text.append("\n· [").append(h.category()).append("] ref ").append(h.code()).append(" · ")
                    .append(h.contactName());
            h.contactPhone().ifPresent(phone -> text.append(" (").append(phone).append(')'));
            text.append(" — ").append(shorten(h.summary()));
        }

        text.append("\n\n").append(es ? "Preguntas sin respuesta" : "Unanswered questions").append(" (")
                .append(c.questions().size()).append(')');
        if (c.questions().isEmpty()) {
            text.append("\n· ").append(es ? "ninguna" : "none");
        }
        for (KnowledgeGap q : c.questions()) {
            text.append("\n· ").append(shorten(q.question()));
        }
        return fit(text.toString(), es);
    }

    /** One line of at most {@link #MAX_SUMMARY_CHARS}, cut at a word boundary: never "cuánto co…". */
    static String shorten(String text) {
        String oneLine = text.replaceAll("\\s+", " ").strip();
        if (oneLine.length() <= MAX_SUMMARY_CHARS) {
            return oneLine;
        }
        // The last space that leaves room for the ellipsis.
        int space = oneLine.lastIndexOf(' ', MAX_SUMMARY_CHARS - 1);
        // A single word longer than the line is cut where it must be.
        String cut = oneLine.substring(0, space > 0 ? space : MAX_SUMMARY_CHARS - 1);
        return cut.replaceAll("[\\s,;:.\\-–—]+$", "") + "…";
    }

    /**
     * Keeps the summary within one channel message, cut at the end of a line so no entry is left
     * half-written; the full lists stay readable over the API.
     */
    private static String fit(String text, boolean es) {
        if (text.length() <= MAX_LENGTH) {
            return text;
        }
        String more = es ? "\n… (el resto, en la API)" : "\n… (the rest, over the API)";
        String head = text.substring(0, MAX_LENGTH - more.length() + 1);
        int lineEnd = head.lastIndexOf('\n');
        return (lineEnd > 0 ? head.substring(0, lineEnd) : head.substring(0, head.length() - 1)) + more;
    }
}
