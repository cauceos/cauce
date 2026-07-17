package dev.cauce.memory.agent;

import dev.cauce.core.agent.AgentStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link AgentEntity}. Derived queries only for now.
 */
public interface AgentRepository extends JpaRepository<AgentEntity, UUID> {

    /** First keyset page of a tenant's agents, ordered by id (UUIDv7: creation order). */
    List<AgentEntity> findByTenantIdOrderByIdAsc(UUID tenantId, Limit limit);

    /** Keyset page after the cursor: agents with {@code id > afterId}, ordered by id. */
    List<AgentEntity> findByTenantIdAndIdGreaterThanOrderByIdAsc(
            UUID tenantId, UUID afterId, Limit limit);

    List<AgentEntity> findByStatus(AgentStatus status);
}
