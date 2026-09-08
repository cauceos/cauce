package dev.cauce.orchestration.usage;

import dev.cauce.orchestration.persistence.LlmUsageRecordMapper;
import dev.cauce.orchestration.persistence.LlmUsageRecordRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side of the LLM usage ledger. Separate from {@link LlmUsageRecorder} on purpose: that
 * one writes a billing fact inside the orchestration loop, this one answers questions about
 * facts already written, and the two have no reason to share a lifecycle.
 *
 * <p>A {@code @Service} with {@code @Transactional} methods, so {@code RlsContextAspect}
 * establishes the RLS context from {@code TenantContext} before each read: usage rows are
 * filtered by the V19 {@code hierarchical_visibility} policy exactly like every other
 * tenant-owned row. An invocation outside the caller's hierarchy yields an empty list, which
 * is indistinguishable from one that recorded nothing — the same deliberate ambiguity the
 * invocation row itself has, and the endpoint 404s on the invocation before it gets here.
 */
@Service
public class LlmUsageQueryService {

    private final LlmUsageRecordRepository repository;
    private final LlmUsageRecordMapper mapper;

    public LlmUsageQueryService(LlmUsageRecordRepository repository, LlmUsageRecordMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    /**
     * Every provider call recorded for one invocation, oldest first. Empty when the
     * invocation recorded none — which is a real outcome, not an error: usage is written
     * only after a provider actually responds, so a call that failed on the way out leaves
     * no row.
     */
    @Transactional(readOnly = true)
    public List<LlmUsageRecord> findByInvocation(UUID invocationId) {
        return repository.findByInvocationIdOrderByCreatedAtAscIdAsc(invocationId).stream()
                .map(mapper::toDomain)
                .toList();
    }
}
