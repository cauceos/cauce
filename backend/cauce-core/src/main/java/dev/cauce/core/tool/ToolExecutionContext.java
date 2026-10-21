package dev.cauce.core.tool;

import java.util.Objects;
import java.util.UUID;

/**
 * Who and what a tool call acts for, built by the orchestrator — never by the model (ADR 0007,
 * B3). A tool takes the subject of its action from here: the tenant whose data it reads or
 * writes, the agent, the conversation, the interlocutor's identity, and the trigger message
 * (the inbound message the invocation answers, which a two-turn confirmation compares against).
 * The model's arguments describe <em>what</em> to do, never <em>to whom</em>.
 *
 * @param tenantId the agent's tenant: the business whose records the tool acts on
 * @param agentId the agent the call was made by
 * @param conversationId the conversation the call belongs to
 * @param identityId the conversation's interlocutor
 * @param triggerMessageId the USER message the invocation answers
 * @param invocationId the invocation being processed
 */
public record ToolExecutionContext(UUID tenantId, UUID agentId, UUID conversationId, UUID identityId,
                                   UUID triggerMessageId, UUID invocationId) {

    public ToolExecutionContext {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(identityId, "identityId");
        Objects.requireNonNull(triggerMessageId, "triggerMessageId");
        Objects.requireNonNull(invocationId, "invocationId");
    }
}
