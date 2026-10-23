package dev.cauce.business.owner;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cauce.core.business.booking.Booking;
import dev.cauce.core.business.handoff.Handoff;
import dev.cauce.core.usage.SpendAlertNotifier.SpendAlert;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OwnerMessagesTest {

    private final Booking.Requester requester = new Booking.Requester(UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

    @Test
    void handoff_putsTheCustomersWordsApart_labelledAndQuoted_afterTheSummary() {
        Handoff handoff = Handoff.open(requester, new Handoff.Request("evento", "Cumpleaños de 25 personas.", "Ana",
                "600 123 456", "¿30 € por persona?"));

        assertThat(OwnerMessages.handoff("es", handoff)).isEqualTo("Nuevo caso para ti [evento] · ref "
                + handoff.code() + "\nAna · 600 123 456\n\nResumen: Cumpleaños de 25 personas.\n\n"
                + "Mensaje del cliente: «¿30 € por persona?»");
        assertThat(OwnerMessages.handoff("en", handoff)).endsWith("\n\nSummary: Cumpleaños de 25 personas.\n\n"
                + "Customer's message: “¿30 € por persona?”");
    }

    @Test
    void handoff_withoutTheCustomersWords_endsWithTheSummary() {
        Handoff handoff = Handoff.open(requester, new Handoff.Request("queja", "La carne llegó fría.", "Luis", null));

        assertThat(OwnerMessages.handoff("es", handoff)).endsWith("Luis\n\nResumen: La carne llegó fría.")
                .doesNotContain("Mensaje del cliente");
    }

    @Test
    void spendNotices_sayWhatWasSpentOfWhat_andWhatHappensAtTheLimit() {
        SpendAlert warning = new SpendAlert(UUID.randomUUID(), UUID.randomUUID(), YearMonth.of(2026, 10), 80,
                new BigDecimal("50.00"), new BigDecimal("40.5"));
        SpendAlert reached = new SpendAlert(UUID.randomUUID(), UUID.randomUUID(), YearMonth.of(2026, 10), 100,
                new BigDecimal("50.00"), new BigDecimal("50.123"));

        assertThat(OwnerMessages.spendWarning("es", warning)).startsWith("Aviso de gasto: en 2026-10 llevas "
                + "40,50 US$ de tu límite de 50,00 US$.").contains("respuesta de respaldo");
        assertThat(OwnerMessages.spendLimitReached("es", reached)).startsWith("Límite de gasto alcanzado: en "
                + "2026-10 se han gastado 50,12 US$ de un límite de 50,00 US$.");
        assertThat(OwnerMessages.spendWarning("en", warning)).contains("40.50 US$ of your 50.00 US$ limit");
    }
}
