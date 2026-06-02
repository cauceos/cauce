package dev.cauce.memory.agent;

import dev.cauce.core.agent.AgentStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link AgentEntity}. Derived queries only for now.
 */
public interface AgentRepository extends JpaRepository<AgentEntity, UUID> {

    List<AgentEntity> findByTenantId(UUID tenantId);

    List<AgentEntity> findByStatus(AgentStatus status);
}
