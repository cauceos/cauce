package dev.cauce.channels.spi;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ReplyMarkdownTest {

    private static final String REPLY = """
            ## Tu reserva
            Reserva confirmada, Ana. Tu código es **9PNVXQ**.
            * Sábado 24, *cena*
            - [La carta](https://bar.example/carta?a=1&b=2)
            Usa `9PNVXQ` si nos llamas.""";

    @Test
    void toPlain_dropsEveryMarker_andSpellsLinksOut() {
        assertThat(ReplyMarkdown.toPlain(REPLY)).isEqualTo("""
                Tu reserva
                Reserva confirmada, Ana. Tu código es 9PNVXQ.
                - Sábado 24, cena
                - La carta (https://bar.example/carta?a=1&b=2)
                Usa 9PNVXQ si nos llamas.""");
    }

    @Test
    void toHtml_writesTelegramsSubset_andEscapesEverythingElse() {
        assertThat(ReplyMarkdown.toHtml(REPLY)).isEqualTo("""
                <b>Tu reserva</b>
                Reserva confirmada, Ana. Tu código es <b>9PNVXQ</b>.
                - Sábado 24, <i>cena</i>
                - <a href="https://bar.example/carta?a=1&amp;b=2">La carta</a>
                Usa <code>9PNVXQ</code> si nos llamas.""");
        assertThat(ReplyMarkdown.toHtml("2 < 3 & 4 > 1")).isEqualTo("2 &lt; 3 &amp; 4 &gt; 1");
    }

    @Test
    void toWhatsApp_writesWhatsAppsOwnMarkers() {
        assertThat(ReplyMarkdown.toWhatsApp("Tu código es **9PNVXQ**, *cena* y ~~comida~~"))
                .isEqualTo("Tu código es *9PNVXQ*, _cena_ y ~comida~");
    }

    @Test
    void markersCountOnlyAroundText_soProseAndIdentifiersStayAsWritten() {
        assertThat(ReplyMarkdown.toPlain("5 * 3 = 15, mesa_terraza_2 y 4*2")).isEqualTo("5 * 3 = 15, mesa_terraza_2 y 4*2");
        assertThat(ReplyMarkdown.toPlain("Tu código es **9PNVXQ")).isEqualTo("Tu código es 9PNVXQ");
        assertThat(ReplyMarkdown.toHtml("```\nlinea <1>\n```")).isEqualTo("<pre>linea &lt;1&gt;</pre>");
        assertThat(ReplyMarkdown.toPlain("**Hecho, _Ana_**")).isEqualTo("Hecho, Ana");
    }
}
