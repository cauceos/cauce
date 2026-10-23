package dev.cauce.evals.cost;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * The prices a run's cost is estimated with ({@code precios.yaml}, ADR 0009, E10): US$ per million
 * tokens, per provider and model, each row with the date its published price was checked. Cost is
 * computed from measured tokens in the report and never stored as a fact.
 */
public record PriceTable(@JsonProperty("version") String version, @JsonProperty("precios") List<Price> prices) {

    public PriceTable {
        prices = prices == null ? List.of() : List.copyOf(prices);
    }

    /**
     * One model's prices, US$ per million tokens. {@code cacheWriteLong} prices the writes made for a
     * long retention (an hour on Anthropic); absent, tokens with such writes have no price
     * ({@link #prices}), like the core's cost view (V51).
     */
    public record Price(
            @JsonProperty("proveedor") String provider,
            @JsonProperty("modelo") String model,
            @JsonProperty("entrada") double input,
            @JsonProperty("cache_escritura") double cacheWrite,
            @JsonProperty("cache_escritura_larga") Double cacheWriteLong,
            @JsonProperty("cache_lectura") double cacheRead,
            @JsonProperty("salida") double output,
            @JsonProperty("comprobado") String checkedOn) {

        /** The cost of the given tokens; {@code input} is the whole prompt, cached parts within it. */
        public double cost(long input, long cacheRead, long cacheWrite, long output) {
            return cost(input, cacheRead, cacheWrite, 0, output);
        }

        /** Whether this price prices {@code cacheWriteLong} long-retention writes: always when there are none. */
        public boolean prices(long cacheWriteLong) {
            return cacheWriteLong == 0 || this.cacheWriteLong != null;
        }

        /**
         * As {@link #cost(long, long, long, long)}, {@code cacheWriteLong} of the writes for a long
         * retention. A reported cost asks {@link #prices} first; without a long-write price this prices
         * them as input, an estimate good only for stopping a run on its budget.
         */
        public double cost(long input, long cacheRead, long cacheWrite, long cacheWriteLong, long output) {
            long uncached = input - cacheRead - cacheWrite;
            double longPrice = this.cacheWriteLong == null ? this.input : this.cacheWriteLong;
            return (uncached * this.input + cacheRead * this.cacheRead + (cacheWrite - cacheWriteLong) * this.cacheWrite
                    + cacheWriteLong * longPrice + output * this.output) / 1_000_000;
        }
    }

    public static PriceTable load(Path file) {
        try {
            return new ObjectMapper(new YAMLFactory()).enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(file.toFile(), PriceTable.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the prices in " + file, e);
        }
    }

    /**
     * The prices of {@code model} by {@code provider}, if the table has them: of several rows, the
     * one checked last (a price change is a new row with its date).
     */
    public Optional<Price> priceOf(String provider, String model) {
        return prices.stream().filter(p -> p.provider().equals(provider) && p.model().equals(model))
                .max(java.util.Comparator.comparing(Price::checkedOn));
    }
}
