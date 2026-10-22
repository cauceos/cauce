package dev.cauce.business.digest;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cauce.business.digest.DigestComposer.Contents;
import dev.cauce.core.business.booking.Agenda;
import dev.cauce.core.business.booking.Agenda.Settings;
import dev.cauce.core.business.booking.Agenda.Shift;
import dev.cauce.core.business.booking.Booking;
import dev.cauce.core.business.gap.KnowledgeGap;
import dev.cauce.core.business.handoff.Handoff;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DigestComposerTest {

    private static final LocalDate TOMORROW = LocalDate.of(2026, 10, 22); // a Thursday
    private final UUID tenantId = UUID.randomUUID();
    private final Booking.Requester requester = new Booking.Requester(tenantId, UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID());
    private final Agenda agenda = Agenda.create(tenantId, new Settings("comensales", 8, 10, 60, false,
            List.of(new Shift("cena", EnumSet.allOf(DayOfWeek.class), LocalTime.of(20, 30), LocalTime.of(23, 0), 50)),
            Set.of()));

    @Test
    void compose_listsTomorrowsBookingsTheOpenCasesAndTheQuestions_inTheBusinessLanguage() {
        Booking booking = Booking.propose(requester, new Booking.Details(TOMORROW, "cena", null, 4, "Ana López",
                "+34 600 000 000", "Trona"), Instant.now().plusSeconds(60)).confirmed();
        Handoff handoff = Handoff.open(requester, new Handoff.Request("evento", "Cumpleaños de 25 personas",
                "Marta", null));
        KnowledgeGap question = KnowledgeGap.record(tenantId, requester.agentId(), requester.conversationId(),
                "¿Tenéis wifi?", Instant.now());

        String text = DigestComposer.compose(new Contents("Bar La Esquina", "es", TOMORROW, Optional.of(agenda),
                List.of(booking), List.of(handoff), List.of(question)));

        assertThat(text).startsWith("Resumen de Bar La Esquina para mañana, jueves 22/10/2026")
                .contains("Reservas (1 · 4 comensales)")
                .contains("· cena 20:30 — 4 — Ana López (+34 600 000 000) · ref " + booking.code() + " · Trona")
                .contains("Casos pendientes (1)\n· [evento] ref " + handoff.code() + " · Marta — Cumpleaños")
                .contains("Preguntas sin respuesta (1)\n· ¿Tenéis wifi?");
    }

    @Test
    void compose_listsTheTimeThePersonAskedFor_insteadOfTheShiftStart() {
        Booking at = Booking.propose(requester, new Booking.Details(TOMORROW, "cena", LocalTime.of(21, 30), 2, "Luis",
                null, null), Instant.now().plusSeconds(60)).confirmed();

        String text = DigestComposer.compose(new Contents("Bar", "es", TOMORROW, Optional.of(agenda), List.of(at),
                List.of(), List.of()));

        assertThat(text).contains("· cena 21:30 — 2 — Luis · ref " + at.code()).doesNotContain("20:30");
    }

    @Test
    void compose_anEmptyDay_saysSo_andEnglishForOtherLanguages() {
        assertThat(DigestComposer.compose(new Contents("Bar", "es", TOMORROW, Optional.empty(), List.of(), List.of(),
                List.of()))).endsWith("Nada pendiente.");
        assertThat(DigestComposer.compose(new Contents("Bar", "de", TOMORROW, Optional.empty(), List.of(), List.of(),
                List.of()))).startsWith("Summary of Bar for tomorrow, Thursday").endsWith("Nothing pending.");
    }

    @Test
    void shorten_cutsALongTextAtAWordBoundary_neverInTheMiddleOfAWord() {
        // The summary of the Telegram test, cut as "... Pregunta cuánto co…" before this fix.
        String summary = "Celebración de cumpleaños para 25 personas, para mañana viernes 23 de octubre de 2026 (no ha "
                + "indicado mediodía o noche). Pregunta cuánto costaría; propone orientativamente 30 € por persona.";

        String shortened = DigestComposer.shorten(summary);

        assertThat(shortened).hasSizeLessThanOrEqualTo(140).endsWith("Pregunta cuánto…");
        assertThat(DigestComposer.shorten("Corto")).isEqualTo("Corto");
        assertThat(DigestComposer.shorten("x".repeat(200))).hasSize(140).endsWith("x…");
    }

    @Test
    void compose_staysWithinOneChannelMessage() {
        List<KnowledgeGap> many = java.util.stream.IntStream.range(0, 200)
                .mapToObj(i -> KnowledgeGap.record(tenantId, requester.agentId(), requester.conversationId(),
                        "Pregunta número " + i + " " + "x".repeat(60), Instant.now()))
                .toList();

        String text = DigestComposer.compose(new Contents("Bar", "es", TOMORROW, Optional.empty(), List.of(), List.of(),
                many));

        assertThat(text).hasSizeLessThanOrEqualTo(DigestComposer.MAX_LENGTH).endsWith("(el resto, en la API)");
        // Cut at the end of a line: the last question listed is whole.
        String[] lines = text.split("\n");
        assertThat(lines[lines.length - 2]).matches("· Pregunta número \\d+ x{60}");
    }
}
