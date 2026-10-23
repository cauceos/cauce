package dev.cauce.evals.cost;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PriceTableTest {

    /** The pack's own price file, as the runner reads it. */
    private static final Path PACK_PRICES = Path.of("../../examples/hosteleria/evals/precios.yaml");

    @Test
    void ofSeveralRowsForAModel_theOneCheckedLastPrices(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("precios.yaml");
        Files.writeString(file, """
                version: 2026-10-23
                precios:
                  - {proveedor: anthropic, modelo: m, entrada: 2, cache_escritura: 2.5, cache_lectura: 0.2, salida: 10, comprobado: 2026-10-22}
                  - {proveedor: anthropic, modelo: m, entrada: 2, cache_escritura: 2.5, cache_lectura: 0.1, salida: 10, comprobado: 2026-10-23}
                  - {proveedor: anthropic, modelo: m, entrada: 2, cache_escritura: 2.5, cache_lectura: 0.3, salida: 10, comprobado: 2026-10-01}
                """);

        assertThat(PriceTable.load(file).priceOf("anthropic", "m")).get()
                .extracting(PriceTable.Price::cacheRead).isEqualTo(0.1);
        assertThat(PriceTable.load(file).priceOf("anthropic", "other")).isEmpty();
    }

    @Test
    void thePacksPrices_haveHaikuAndTheCurrentSonnet() {
        PriceTable table = PriceTable.load(PACK_PRICES);

        PriceTable.Price haiku = table.priceOf("anthropic", "claude-haiku-5-5").orElseThrow();
        // A million tokens of each kind, one at a time, at the published price for prompts up to 100K.
        assertThat(haiku.cost(1_000_000, 0, 0, 0)).isCloseTo(0.10, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(haiku.cost(1_000_000, 1_000_000, 0, 0)).isCloseTo(0.01, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(haiku.cost(1_000_000, 0, 1_000_000, 0)).isCloseTo(0.125, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(haiku.cost(0, 0, 0, 1_000_000)).isCloseTo(0.50, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(table.priceOf("anthropic", "claude-sonnet-5-5").orElseThrow().cacheRead()).isCloseTo(0.10, org.assertj.core.data.Offset.offset(1e-12));
    }
}
