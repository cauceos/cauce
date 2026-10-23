package dev.cauce.api.usage;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Request body for {@code POST /v1/llm-prices}: the provider's list price of a model, in US$ per
 * million tokens (ADR 0010, S1). The cache prices are optional; absent, cached tokens are priced as
 * input. {@code cache_write_long_usd_per_mtok} prices the writes made for a long retention (an hour on
 * Anthropic). There is no {@code valid_from}: a price is in force from the moment it is added.
 */
public record AddLlmPriceRequest(
        @NotBlank String provider,
        @NotBlank String model,
        @NotNull @DecimalMin("0") BigDecimal inputUsdPerMtok,
        @NotNull @DecimalMin("0") BigDecimal outputUsdPerMtok,
        @DecimalMin("0") BigDecimal cacheReadUsdPerMtok,
        @DecimalMin("0") BigDecimal cacheWriteUsdPerMtok,
        @DecimalMin("0") BigDecimal cacheWriteLongUsdPerMtok) {
}
