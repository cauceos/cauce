package dev.cauce.orchestration.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for LLM usage rows. Write-only for now — this unit captures the
 * facts; the query/aggregation surface is a separate future unit (it will arrive with the
 * dashboard, which defines the aggregations it needs), so no finders are declared yet.
 */
public interface LlmUsageRecordRepository extends JpaRepository<LlmUsageRecordEntity, UUID> {
}
