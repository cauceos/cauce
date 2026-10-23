package dev.cauce.api.usage;

import dev.cauce.orchestration.usage.LlmPrice;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One price row, in US$ per million tokens; the cache prices are null when the row has none. */
public record LlmPriceResponse(UUID id,
                               UUID tenantId,
                               String provider,
                               String model,
                               BigDecimal inputUsdPerMtok,
                               BigDecimal outputUsdPerMtok,
                               BigDecimal cacheReadUsdPerMtok,
                               BigDecimal cacheWriteUsdPerMtok,
                               Instant validFrom) {

    public static LlmPriceResponse from(LlmPrice price) {
        return new LlmPriceResponse(price.id(), price.tenantId(), price.provider(), price.model(),
                price.inputUsdPerMtok(), price.outputUsdPerMtok(), price.cacheReadUsdPerMtok(),
                price.cacheWriteUsdPerMtok(), price.validFrom());
    }
}
