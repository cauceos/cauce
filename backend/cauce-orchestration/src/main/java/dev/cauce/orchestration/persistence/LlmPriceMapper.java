package dev.cauce.orchestration.persistence;

import dev.cauce.orchestration.usage.LlmPrice;
import org.springframework.stereotype.Component;

/**
 * Hand-written bidirectional mapping between the domain {@link LlmPrice} and its JPA
 * {@link LlmPriceEntity}.
 */
@Component
public final class LlmPriceMapper {

    public LlmPriceEntity toEntity(LlmPrice price) {
        return new LlmPriceEntity(price.id(), price.tenantId(), price.provider(), price.model(),
                price.inputUsdPerMtok(), price.outputUsdPerMtok(), price.cacheReadUsdPerMtok(),
                price.cacheWriteUsdPerMtok(), price.cacheWriteLongUsdPerMtok(), price.validFrom(),
                price.audioUsdPerMinute());
    }

    public LlmPrice toDomain(LlmPriceEntity entity) {
        return new LlmPrice(entity.getId(), entity.getTenantId(), entity.getProvider(), entity.getModel(),
                entity.getInputUsdPerMtok(), entity.getOutputUsdPerMtok(), entity.getCacheReadUsdPerMtok(),
                entity.getCacheWriteUsdPerMtok(), entity.getCacheWriteLongUsdPerMtok(), entity.getValidFrom(),
                entity.getAudioUsdPerMinute());
    }
}
