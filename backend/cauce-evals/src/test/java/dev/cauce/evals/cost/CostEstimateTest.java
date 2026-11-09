package dev.cauce.evals.cost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CostEstimateTest {

    private static final PriceTable PRICES = PriceTable.load(Path.of("..", "..", "examples", "hosteleria", "evals",
            "precios.yaml"));

    @Test
    void thePacksPrices_load_andPriceCachedPartsApart() {
        PriceTable.Price sonnet = PRICES.priceOf("anthropic", "claude-sonnet-5-5").orElseThrow();

        // 1M input of which 800k read (0.10 since 2026-10-23) and 100k written, 10k output.
        assertThat(sonnet.cost(1_000_000, 800_000, 100_000, 10_000)).isCloseTo(0.2 + 0.08 + 0.25 + 0.1, within(1e-9));
        assertThat(PRICES.priceOf("openai", "otro")).isEmpty();
    }

    @Test
    void theEstimate_pricesTheInputWithTheCacheShareOfTheLastRunOfTheModel_orUncachedWithoutOne() {
        Optional<PriceTable.Price> sonnet = PRICES.priceOf("anthropic", "claude-sonnet-5-5");
        // 1555Z: 4,745,460 input over 320 model turns, 4,632,209 read from the cache, 111,247 written for an hour.
        Optional<CostEstimate.CacheShare> share = CostEstimate.CacheShare.of(4_745_460, 4_632_209, 111_247, 111_247);

        CostEstimate measured = CostEstimate.of(320, 0, 14_829L, 230L, share, sonnet, Optional.empty());
        CostEstimate ceiling = CostEstimate.of(320, 0, 14_829L, 230L, Optional.empty(), sonnet, Optional.empty());

        assertThat(measured.usd()).hasValueSatisfying(usd -> assertThat(usd).isBetween(1.4, 1.9));
        assertThat(ceiling.usd()).hasValueSatisfying(usd -> assertThat(usd).isGreaterThan(9.5));
        assertThat(measured.basis()).contains("97.6 %");
        assertThat(ceiling.basis()).contains("sin caché");
        assertThat(CostEstimate.CacheShare.of(0, 0, 0, 0)).isEmpty();
    }

    @Test
    void theEstimate_usesThePreviousRunOrTheDefaults_andWithoutAPriceItAlwaysNeedsConfirmation() {
        Optional<PriceTable.Price> sonnet = PRICES.priceOf("anthropic", "claude-sonnet-5-5");

        CostEstimate first = CostEstimate.of(100, 0, null, null, sonnet, Optional.empty());
        CostEstimate measured = CostEstimate.of(100, 0, 5_000L, 200L, sonnet, Optional.empty());
        CostEstimate unpriced = CostEstimate.of(100, 0, null, null, Optional.empty(), Optional.empty());

        assertThat(first.usd()).hasValueSatisfying(usd -> assertThat(usd).isCloseTo(2.25, within(1e-9)));
        assertThat(measured.usd()).hasValueSatisfying(usd -> assertThat(usd).isCloseTo(1.2, within(1e-9)));
        assertThat(first.exceeds(10)).isFalse();
        assertThat(first.exceeds(2)).isTrue();
        assertThat(unpriced.exceeds(1000)).isTrue();
    }
}
