package dev.cauce.api.usage;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Request body for {@code POST /v1/llm-prices}: the provider's list price of a model, in US$ per
 * million tokens (ADR 0010, S1). The cache prices are optional; absent, cached tokens are priced as
 * input. {@code cache_write_long_usd_per_mtok} prices the writes made for a long retention (an hour on
 * Anthropic). {@code audio_usd_per_minute} prices a minute of transcribed audio (ADR 0012): a
 * transcriber's row, with zero token prices. There is no {@code valid_from}: a price is in force from
 * the moment it is added.
 */
public record AddLlmPriceRequest(
        @NotBlank String provider,
        @NotBlank String model,
        @NotNull @DecimalMin("0") BigDecimal inputUsdPerMtok,
        @NotNull @DecimalMin("0") BigDecimal outputUsdPerMtok,
        @DecimalMin("0") BigDecimal cacheReadUsdPerMtok,
        @DecimalMin("0") BigDecimal cacheWriteUsdPerMtok,
        @DecimalMin("0") BigDecimal cacheWriteLongUsdPerMtok,
        @DecimalMin("0") BigDecimal audioUsdPerMinute) {

    /** A request that does not price audio. */
    public AddLlmPriceRequest(String provider, String model, BigDecimal inputUsdPerMtok, BigDecimal outputUsdPerMtok,
                              BigDecimal cacheReadUsdPerMtok, BigDecimal cacheWriteUsdPerMtok,
                              BigDecimal cacheWriteLongUsdPerMtok) {
        this(provider, model, inputUsdPerMtok, outputUsdPerMtok, cacheReadUsdPerMtok, cacheWriteUsdPerMtok,
                cacheWriteLongUsdPerMtok, null);
    }
}
