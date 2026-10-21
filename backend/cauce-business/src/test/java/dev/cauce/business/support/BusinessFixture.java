package dev.cauce.business.support;

import dev.cauce.core.agent.Agent;
import dev.cauce.core.conversation.Conversation;
import dev.cauce.core.identity.IdentityKind;
import dev.cauce.core.tenant.Tenant;
import dev.cauce.core.tenant.TenantContext;
import dev.cauce.tenancy.AgentService;
import dev.cauce.tenancy.ConversationService;
import dev.cauce.tenancy.TenantService;
import java.util.function.Supplier;

/**
 * The hierarchy every business IT starts from, seeded through the real services: an operator, a
 * partner, two businesses (clients) of that partner, an agent of business A and one conversation of
 * that agent with an {@code api} interlocutor.
 */
public record BusinessFixture(Tenant operator, Tenant partner, Tenant clientA, Tenant clientB, Agent agent,
                              Conversation conversation) {

    public static BusinessFixture seed(TenantService tenantService, AgentService agentService,
                                       ConversationService conversationService) {
        TenantContext.clear();
        Tenant operator = tenantService.bootstrapOperator("Operator");
        Tenant partner = as(operator, () -> tenantService.createPartner("Partner", operator.id()));
        Tenant clientA = as(partner, () -> tenantService.createClient("Bar A", partner.id()));
        Tenant clientB = as(partner, () -> tenantService.createClient("Bar B", partner.id()));
        Agent agent = as(clientA, () -> agentService.createAgent(clientA.id(), "Camarero",
                "Eres el camarero del bar.", "anthropic", "claude-sonnet-4-7"));
        Conversation conversation = as(clientA, () -> conversationService.startConversation(agent.id(), "api",
                IdentityKind.CLIENT_REFERENCE, "cliente-1", null));
        return new BusinessFixture(operator, partner, clientA, clientB, agent, conversation);
    }

    /** Runs {@code action} with {@code tenant} as the current context, then clears it. */
    public static <T> T as(Tenant tenant, Supplier<T> action) {
        TenantContext.setCurrentTenantId(tenant.id());
        try {
            return action.get();
        } finally {
            TenantContext.clear();
        }
    }
}
