package dev.cauce.orchestration.usage;

import dev.cauce.core.audit.AuditEventRecorder;
import dev.cauce.core.tenant.InvalidTenantTierException;
import dev.cauce.core.tenant.Tenant;
import dev.cauce.core.tenant.TenantContext;
import dev.cauce.core.tenant.Tier;
import dev.cauce.orchestration.audit.UsageAuditEvents;
import dev.cauce.orchestration.persistence.LlmPriceMapper;
import dev.cauce.orchestration.persistence.LlmPriceRepository;
import dev.cauce.tenancy.TenantService;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The price table (ADR 0010, S1): an operator adds the provider's list price of a model, in force
 * from the moment it is added; every tenant reads the prices of its hierarchy. Prices are never
 * changed or removed: a new price is a new row.
 */
@Service
public class LlmPriceService {

    // TODO(cauce-llm): validate against the registered providers once the SPI exposes them, like
    // the agent's model_provider (AgentService).
    private static final Set<String> SUPPORTED_PROVIDERS = Set.of("openai", "anthropic", "mistral", "ollama");

    private final LlmPriceRepository repository;
    private final LlmPriceMapper mapper;
    private final TenantService tenantService;
    private final AuditEventRecorder auditRecorder;
    private final Clock clock;

    public LlmPriceService(LlmPriceRepository repository, LlmPriceMapper mapper, TenantService tenantService,
                           AuditEventRecorder auditRecorder, Clock clock) {
        this.repository = repository;
        this.mapper = mapper;
        this.tenantService = tenantService;
        this.auditRecorder = auditRecorder;
        this.clock = clock;
    }

    /**
     * Adds a price owned by the current tenant, which must be the operator, in force from now (the
     * database clock). Audited in the operator's chain.
     *
     * @throws InvalidTenantTierException if the current tenant is not an OPERATOR
     * @throws IllegalArgumentException if the provider is not supported or a price is negative
     */
    @Transactional
    public LlmPrice addPrice(String provider, String model, BigDecimal inputUsdPerMtok, BigDecimal outputUsdPerMtok,
                             BigDecimal cacheReadUsdPerMtok, BigDecimal cacheWriteUsdPerMtok,
                             BigDecimal cacheWriteLongUsdPerMtok) {
        UUID actorId = TenantContext.getCurrentTenantId().orElseThrow();
        Tenant actor = tenantService.getTenant(actorId);
        if (actor.tier() != Tier.OPERATOR) {
            throw new InvalidTenantTierException("Only an OPERATOR adds prices, but the caller is " + actor.tier());
        }
        if (!SUPPORTED_PROVIDERS.contains(provider)) {
            throw new IllegalArgumentException("Unsupported model provider: " + provider);
        }
        LlmPrice price = LlmPrice.create(actorId, provider, model, inputUsdPerMtok, outputUsdPerMtok,
                cacheReadUsdPerMtok, cacheWriteUsdPerMtok, cacheWriteLongUsdPerMtok, clock.instant());
        LlmPrice saved = mapper.toDomain(repository.save(mapper.toEntity(price)));
        auditRecorder.record(UsageAuditEvents.llmPriceAdded(saved, actorId));
        return saved;
    }

    /**
     * Up to {@code maxRows} prices the current tenant can read (its own, its ancestors', its
     * descendants'), in the order they were added, after {@code afterId} when non-null.
     */
    @Transactional(readOnly = true)
    public List<LlmPrice> listPrices(UUID afterId, int maxRows) {
        return (afterId == null
                ? repository.findAllByOrderByIdAsc(Limit.of(maxRows))
                : repository.findByIdGreaterThanOrderByIdAsc(afterId, Limit.of(maxRows)))
                .stream().map(mapper::toDomain).toList();
    }
}
