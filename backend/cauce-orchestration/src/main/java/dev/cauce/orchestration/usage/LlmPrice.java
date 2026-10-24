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
 * cache prices are optional; absent, cached tokens are priced as input. {@code cacheWriteLongUsdPerMtok}
 * prices the writes made for a long retention (an hour on Anthropic, V49). {@code audioUsdPerMinute}
 * prices a minute of transcribed audio (ADR 0012, V4; V56); a transcriber's price row has it, and
 * zero token prices. Owned by the operator tenant.
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
                       BigDecimal cacheWriteLongUsdPerMtok,
                       Instant validFrom,
                       BigDecimal audioUsdPerMinute) {

    /** A price that does not price audio. */
    public LlmPrice(UUID id, UUID tenantId, String provider, String model, BigDecimal inputUsdPerMtok,
                    BigDecimal outputUsdPerMtok, BigDecimal cacheReadUsdPerMtok, BigDecimal cacheWriteUsdPerMtok,
                    BigDecimal cacheWriteLongUsdPerMtok, Instant validFrom) {
        this(id, tenantId, provider, model, inputUsdPerMtok, outputUsdPerMtok, cacheReadUsdPerMtok,
                cacheWriteUsdPerMtok, cacheWriteLongUsdPerMtok, validFrom, null);
    }

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
        requireNotNegative(cacheWriteLongUsdPerMtok, "cacheWriteLongUsdPerMtok");
        requireNotNegative(audioUsdPerMinute, "audioUsdPerMinute");
    }

    /**
     * Mints a new price of {@code tenantId}, in force from {@code now} — the application clock's
     * reading, the database's (ADR 0008).
     */
    public static LlmPrice create(UUID tenantId, String provider, String model, BigDecimal inputUsdPerMtok,
                                  BigDecimal outputUsdPerMtok, BigDecimal cacheReadUsdPerMtok,
                                  BigDecimal cacheWriteUsdPerMtok, Instant now) {
        return create(tenantId, provider, model, inputUsdPerMtok, outputUsdPerMtok, cacheReadUsdPerMtok,
                cacheWriteUsdPerMtok, null, now);
    }

    /** As {@link #create(UUID, String, String, BigDecimal, BigDecimal, BigDecimal, BigDecimal, Instant)}, with the long-retention write price. */
    public static LlmPrice create(UUID tenantId, String provider, String model, BigDecimal inputUsdPerMtok,
                                  BigDecimal outputUsdPerMtok, BigDecimal cacheReadUsdPerMtok,
                                  BigDecimal cacheWriteUsdPerMtok, BigDecimal cacheWriteLongUsdPerMtok,
                                  Instant now) {
        return create(tenantId, provider, model, inputUsdPerMtok, outputUsdPerMtok, cacheReadUsdPerMtok,
                cacheWriteUsdPerMtok, cacheWriteLongUsdPerMtok, null, now);
    }

    /** As the others, with the price of a minute of transcribed audio. */
    public static LlmPrice create(UUID tenantId, String provider, String model, BigDecimal inputUsdPerMtok,
                                  BigDecimal outputUsdPerMtok, BigDecimal cacheReadUsdPerMtok,
                                  BigDecimal cacheWriteUsdPerMtok, BigDecimal cacheWriteLongUsdPerMtok,
                                  BigDecimal audioUsdPerMinute, Instant now) {
        return new LlmPrice(UuidGenerator.newV7(), tenantId, provider, model, inputUsdPerMtok, outputUsdPerMtok,
                cacheReadUsdPerMtok, cacheWriteUsdPerMtok, cacheWriteLongUsdPerMtok, now, audioUsdPerMinute);
    }

    public Optional<BigDecimal> cacheRead() {
        return Optional.ofNullable(cacheReadUsdPerMtok);
    }

    public Optional<BigDecimal> cacheWrite() {
        return Optional.ofNullable(cacheWriteUsdPerMtok);
    }

    public Optional<BigDecimal> cacheWriteLong() {
        return Optional.ofNullable(cacheWriteLongUsdPerMtok);
    }

    public Optional<BigDecimal> audioPerMinute() {
        return Optional.ofNullable(audioUsdPerMinute);
    }

    private static void requireNotNegative(BigDecimal value, String name) {
        if (value != null && value.signum() < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }
}
