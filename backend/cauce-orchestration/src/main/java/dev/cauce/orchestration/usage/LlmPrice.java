package dev.cauce.orchestration.usage;

import dev.cauce.core.UuidGenerator;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The provider's list price of one model, in US$ per million tokens, in force from
 * {@code validFrom} until a later price of the same provider and model (ADR 0010, S1). Never
 * changed once added: a new price is a new row, so a tariff change never reprices past calls. The
 * cache prices are optional; absent, cached tokens are priced as input. Owned by the operator
 * tenant.
 *
 * <p>{@code provider} is a {@code String}: its vocabulary is the cauce-llm SPI's.
 */
public record LlmPrice(UUID id,
                       UUID tenantId,
                       String provider,
                       String model,
                       BigDecimal inputUsdPerMtok,
                       BigDecimal outputUsdPerMtok,
                       BigDecimal cacheReadUsdPerMtok,
                       BigDecimal cacheWriteUsdPerMtok,
                       Instant validFrom) {

    public LlmPrice {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(provider, "provider must not be null");
        Objects.requireNonNull(model, "model must not be null");
        Objects.requireNonNull(inputUsdPerMtok, "inputUsdPerMtok must not be null");
        Objects.requireNonNull(outputUsdPerMtok, "outputUsdPerMtok must not be null");
        Objects.requireNonNull(validFrom, "validFrom must not be null");
        if (provider.isBlank()) {
            throw new IllegalArgumentException("provider must not be blank");
        }
        if (model.isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
        requireNotNegative(inputUsdPerMtok, "inputUsdPerMtok");
        requireNotNegative(outputUsdPerMtok, "outputUsdPerMtok");
        requireNotNegative(cacheReadUsdPerMtok, "cacheReadUsdPerMtok");
        requireNotNegative(cacheWriteUsdPerMtok, "cacheWriteUsdPerMtok");
    }

    /**
     * Mints a new price of {@code tenantId}, in force from {@code now} — the application clock's
     * reading, the database's (ADR 0008).
     */
    public static LlmPrice create(UUID tenantId, String provider, String model, BigDecimal inputUsdPerMtok,
                                  BigDecimal outputUsdPerMtok, BigDecimal cacheReadUsdPerMtok,
                                  BigDecimal cacheWriteUsdPerMtok, Instant now) {
        return new LlmPrice(UuidGenerator.newV7(), tenantId, provider, model, inputUsdPerMtok, outputUsdPerMtok,
                cacheReadUsdPerMtok, cacheWriteUsdPerMtok, now);
    }

    public Optional<BigDecimal> cacheRead() {
        return Optional.ofNullable(cacheReadUsdPerMtok);
    }

    public Optional<BigDecimal> cacheWrite() {
        return Optional.ofNullable(cacheWriteUsdPerMtok);
    }

    private static void requireNotNegative(BigDecimal value, String name) {
        if (value != null && value.signum() < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }
}
