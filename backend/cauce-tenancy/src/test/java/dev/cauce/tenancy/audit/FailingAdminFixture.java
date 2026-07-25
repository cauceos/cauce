package dev.cauce.tenancy.audit;

import dev.cauce.tenancy.AgentService;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Test stand-in for an admin caller whose transaction fails AFTER a complete, real admin
 * operation ran inside it: {@code createAgent} joins this method's transaction (REQUIRED
 * propagation), so the thrown exception rolls back the agent row AND the admin audit
 * capture together. Lets the admin IT prove "absence of the fact ⇔ absence of the audit
 * record" against a real administrative operation.
 */
@Service
public class FailingAdminFixture {

    private final AgentService agentService;

    public FailingAdminFixture(AgentService agentService) {
        this.agentService = agentService;
    }

    /** Runs a full real createAgent, then fails the surrounding transaction. */
    @Transactional
    public void createAgentThenFail(UUID tenantId) {
        agentService.createAgent(tenantId, "DoomedBot", "You will be rolled back.",
                "anthropic", "claude-sonnet-4-7");
        throw new IllegalStateException("simulated admin failure after create");
    }
}
