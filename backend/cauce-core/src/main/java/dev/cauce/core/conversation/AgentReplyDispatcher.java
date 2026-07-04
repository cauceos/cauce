package dev.cauce.core.conversation;

import dev.cauce.core.message.Message;

/**
 * Outbound port (hexagonal, invariant 2): hands a persisted agent reply to whatever can
 * deliver it back through the conversation's channel of origin. The domain defines the
 * seam; the channel layer implements it (resolving the channel instance and driving the
 * provider), and the orchestrator invokes the abstraction after the reply has committed —
 * the core never knows about Telegram, WhatsApp, or HTTP.
 *
 * <p><strong>Best-effort contract.</strong> Implementations must never throw into the
 * caller and must not block it on network I/O: delivery failures are their own concern
 * (logged, swallowed), because the reply is already persisted and remains readable by
 * polling the conversation messages. A channel without an outbound half (e.g. the built-in
 * {@code "api"} channel) is a clean no-op.
 *
 * <p>TODO(delivery guarantee): when traffic justifies guaranteed delivery, this seam is
 * where the outbox pattern enters — persist the delivery intent here and drain it from a
 * scheduled dispatcher — as a pure addition behind the same port, not a refactor of the
 * orchestrator.
 */
public interface AgentReplyDispatcher {

    /**
     * Dispatches {@code agentReply} (already persisted and committed) back through
     * {@code conversation}'s channel of origin.
     */
    void dispatchAgentReply(Conversation conversation, Message agentReply);
}
