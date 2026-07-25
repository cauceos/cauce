package dev.cauce.orchestration.usage;

import dev.cauce.orchestration.persistence.LlmUsageRecordMapper;
import dev.cauce.orchestration.persistence.LlmUsageRecordRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenant-scoped writer for the LLM usage ledger, invoked synchronously by the orchestrator
 * immediately after each provider response and before the {@code LlmResponded} event is
 * published. Like {@code ConversationGateway}, each call is a short {@code @Transactional}
 * unit advised by {@code RlsContextAspect} — the (non-transactional) loop driver commits the
 * usage fact before continuing, so a recorded LLM call is never lost silently: if this INSERT
 * fails, the exception propagates and the invocation fails, the same failure class as a
 * message append (same database, same thread).
 *
 * <p>Separate bean on purpose: a self-invoked {@code @Transactional} method on the
 * orchestrator would bypass the Spring proxy and silently run with no transaction (and no
 * RLS). Deliberately NOT an event consumer — billing facts do not ride the observational
 * event stream.
 */
@Service
public class LlmUsageRecorder {

    private final LlmUsageRecordRepository repository;
    private final LlmUsageRecordMapper mapper;

    public LlmUsageRecorder(LlmUsageRecordRepository repository, LlmUsageRecordMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    /** Persists one LLM call's usage in its own transaction. */
    @Transactional
    public void record(LlmUsageRecord record) {
        repository.save(mapper.toEntity(record));
    }
}
