package dev.cauce.orchestration.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA persistence mapping for one price row (V46). The domain type is
 * {@link dev.cauce.orchestration.usage.LlmPrice}, converted by {@link LlmPriceMapper}. Rows are
 * insert-only — every column is {@code updatable = false}, and V46 has no UPDATE policy.
 */
@Entity
@Table(name = "llm_prices")
public class LlmPriceEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "provider", nullable = false, updatable = false, length = 20)
    private String provider;

    @Column(name = "model", nullable = false, updatable = false, length = 100)
    private String model;

    @Column(name = "input_usd_per_mtok", nullable = false, updatable = false, precision = 12, scale = 6)
    private BigDecimal inputUsdPerMtok;

    @Column(name = "output_usd_per_mtok", nullable = false, updatable = false, precision = 12, scale = 6)
    private BigDecimal outputUsdPerMtok;

    @Column(name = "cache_read_usd_per_mtok", updatable = false, precision = 12, scale = 6)
    private BigDecimal cacheReadUsdPerMtok;

    @Column(name = "cache_write_usd_per_mtok", updatable = false, precision = 12, scale = 6)
    private BigDecimal cacheWriteUsdPerMtok;

    @Column(name = "valid_from", nullable = false, updatable = false)
    private Instant validFrom;

    protected LlmPriceEntity() {
        // for JPA
    }

    public LlmPriceEntity(UUID id, UUID tenantId, String provider, String model, BigDecimal inputUsdPerMtok,
                          BigDecimal outputUsdPerMtok, BigDecimal cacheReadUsdPerMtok,
                          BigDecimal cacheWriteUsdPerMtok, Instant validFrom) {
        this.id = id;
        this.tenantId = tenantId;
        this.provider = provider;
        this.model = model;
        this.inputUsdPerMtok = inputUsdPerMtok;
        this.outputUsdPerMtok = outputUsdPerMtok;
        this.cacheReadUsdPerMtok = cacheReadUsdPerMtok;
        this.cacheWriteUsdPerMtok = cacheWriteUsdPerMtok;
        this.validFrom = validFrom;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getProvider() {
        return provider;
    }

    public String getModel() {
        return model;
    }

    public BigDecimal getInputUsdPerMtok() {
        return inputUsdPerMtok;
    }

    public BigDecimal getOutputUsdPerMtok() {
        return outputUsdPerMtok;
    }

    public BigDecimal getCacheReadUsdPerMtok() {
        return cacheReadUsdPerMtok;
    }

    public BigDecimal getCacheWriteUsdPerMtok() {
        return cacheWriteUsdPerMtok;
    }

    public Instant getValidFrom() {
        return validFrom;
    }
}
