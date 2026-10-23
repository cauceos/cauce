package dev.cauce.orchestration.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for price rows. Every query runs under the V46 policies: a tenant reads
 * the prices of itself, its ancestors and its descendants. Keyset pages on the UUIDv7 id, compared
 * in Postgres.
 */
public interface LlmPriceRepository extends JpaRepository<LlmPriceEntity, UUID> {

    List<LlmPriceEntity> findAllByOrderByIdAsc(Limit limit);

    List<LlmPriceEntity> findByIdGreaterThanOrderByIdAsc(UUID afterId, Limit limit);
}
