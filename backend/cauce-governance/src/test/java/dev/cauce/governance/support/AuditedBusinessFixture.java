package dev.cauce.governance.support;

import dev.cauce.core.agent.Agent;
import dev.cauce.core.audit.AuditEvent;
import dev.cauce.core.audit.AuditEventRecorder;
import dev.cauce.memory.agent.AgentMapper;
import dev.cauce.memory.agent.AgentRepository;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Test stand-in for a future production call site of {@link AuditEventRecorder}: one
 * {@code @Transactional} method that performs a business write (an agent row, via
 * cauce-memory) and records its audit event as one line inside the same transaction. Lets
 * the ITs prove capture atomicity — commit keeps both rows, rollback keeps neither — through
 * the real proxy + RlsContextAspect path.
 */
@Service
public class AuditedBusinessFixture {

    private final AgentRepository agentRepository;
    private final AgentMapper agentMapper;
    private final AuditEventRecorder recorder;

    public AuditedBusinessFixture(AgentRepository agentRepository, AgentMapper agentMapper,
                                  AuditEventRecorder recorder) {
        this.agentRepository = agentRepository;
        this.agentMapper = agentMapper;
        this.recorder = recorder;
    }

    /**
     * Creates an agent and records the matching audit event in one transaction. With
     * {@code failAfterBothWrites} the method throws AFTER both writes executed, so a rollback
     * must leave neither row behind.
     */
    @Transactional
    public UUID createAgentWithAudit(UUID tenantId, boolean failAfterBothWrites) {
        Agent agent = Agent.create(tenantId, "AuditedBot", "You are audited.", "anthropic",
                "claude-sonnet-4-7");
        agentRepository.save(agentMapper.toEntity(agent));
        recorder.record(new AuditEvent(tenantId, "test.agent_created",
                Map.of("agent_id", agent.id().toString())));
        if (failAfterBothWrites) {
            throw new IllegalStateException("simulated business failure after both writes");
        }
        return agent.id();
    }

    /** Captures a bare audit event in its own transaction (drain-test seeding). */
    @Transactional
    public void recordOnly(UUID tenantId, String eventType) {
        recorder.record(new AuditEvent(tenantId, eventType, Map.of("seed", eventType)));
    }
}
