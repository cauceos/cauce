package dev.cauce.orchestration.service;

import dev.cauce.core.conversation.AgentReplyDispatcher;
import dev.cauce.core.message.Message;
import dev.cauce.orchestration.LogSafe;
import dev.cauce.orchestration.events.InvocationFailureType;
import dev.cauce.orchestration.service.ConversationGateway.ConversationAgent;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Answers the interlocutor when an invocation failed for good (ADR 0005, C3): persists a
 * FALLBACK message with the agent's fallback text (or the platform default) and the failure
 * type it answers, then hands it to the channel exactly like a reply. The internal SYSTEM error
 * record, where there is one, stays as it is; the fallback carries no internal detail.
 *
 * <p>Called only after a terminal transition (FAILED or ABANDONED) has committed — by the worker
 * and by the reaper — never for a failure that will be retried. Best-effort by contract, like
 * reply delivery: a failure here is logged and swallowed, because the invocation has already
 * failed and its status is the authoritative record. Runs under the caller's tenant context.
 */
@Service
public class FailureFallbackService {

    private static final Logger log = LoggerFactory.getLogger(FailureFallbackService.class);

    private final ConversationGateway conversationGateway;
    private final FallbackReplyProperties properties;
    private final ObjectProvider<AgentReplyDispatcher> replyDispatcher;

    public FailureFallbackService(ConversationGateway conversationGateway,
                                  FallbackReplyProperties properties,
                                  ObjectProvider<AgentReplyDispatcher> replyDispatcher) {
        this.conversationGateway = conversationGateway;
        this.properties = properties;
        this.replyDispatcher = replyDispatcher;
    }

    /** Sends the fallback for a terminal failure of {@code failureType} in {@code conversationId}. */
    public void sendFallback(UUID conversationId, InvocationFailureType failureType) {
        Message fallback;
        ConversationAgent target;
        try {
            target = conversationGateway.loadConversationAndAgent(conversationId);
            String text = target.agent().fallbackReply().orElse(properties.getDefaultReply());
            fallback = conversationGateway.append(Message.fallback(conversationId, text, failureType.name()));
        } catch (RuntimeException e) {
            log.warn("Could not record the fallback reply for conversation {} ({}): {}",
                    conversationId, failureType, LogSafe.describe(e));
            log.debug("Fallback failure detail", e);
            return;
        }
        AgentReplyDispatcher dispatcher = replyDispatcher.getIfAvailable();
        if (dispatcher == null) {
            return;
        }
        try {
            dispatcher.dispatchAgentReply(target.conversation(), fallback);
        } catch (RuntimeException e) {
            log.warn("Fallback dispatch failed for conversation {}: {}", conversationId, LogSafe.describe(e));
            log.debug("Fallback dispatch failure detail", e);
        }
    }
}
