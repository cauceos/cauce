package dev.cauce.business.gap;

import dev.cauce.core.business.gap.KnowledgeGap;
import dev.cauce.core.business.gap.KnowledgeGapSink;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Unanswered questions (ADR 0007, B4): the native adapter of the {@link KnowledgeGapSink} port,
 * and their listing for the owner. Tenant-scoped under RLS.
 */
@Service
public class KnowledgeGapService implements KnowledgeGapSink {

    private final KnowledgeGapRepository repository;
    private final KnowledgeGapMapper mapper;

    public KnowledgeGapService(KnowledgeGapRepository repository, KnowledgeGapMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public KnowledgeGap recordUnansweredQuestion(UUID tenantId, UUID agentId, UUID conversationId, String question) {
        KnowledgeGap gap = KnowledgeGap.record(tenantId, agentId, conversationId, question);
        return mapper.toDomain(repository.save(mapper.toEntity(gap)));
    }

    /**
     * Up to {@code maxRows} questions of the business {@code tenantId}, oldest first, after
     * {@code afterId} when non-null (keyset pagination). RLS filters out tenants the context
     * cannot see.
     */
    @Transactional(readOnly = true)
    public List<KnowledgeGap> listForTenant(UUID tenantId, UUID afterId, int maxRows) {
        List<KnowledgeGapEntity> page = afterId == null
                ? repository.findByTenantIdOrderByIdAsc(tenantId, Limit.of(maxRows))
                : repository.findByTenantIdAndIdGreaterThanOrderByIdAsc(tenantId, afterId, Limit.of(maxRows));
        return page.stream().map(mapper::toDomain).toList();
    }

    /** The questions recorded in [{@code from}, {@code to}), oldest first: a digest's window. */
    @Transactional(readOnly = true)
    public List<KnowledgeGap> listRecordedBetween(UUID tenantId, Instant from, Instant to) {
        return repository.findByTenantIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAsc(
                tenantId, from, to).stream().map(mapper::toDomain).toList();
    }
}
