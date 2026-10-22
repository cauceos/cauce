package dev.cauce.business.owner;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cauce.core.business.booking.Booking;
import dev.cauce.core.business.handoff.Handoff;
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
}
