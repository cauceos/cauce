package dev.cauce.core;

/**
 * The keys under which the ids of what a thread is working on go into the logging context (MDC)
 * (ADR 0011, L1): every log line of that work carries them, and structured (JSON) logs emit them as
 * fields. Ids only: never a message's content, an identity's value or a provider's error detail.
 * Plain strings, so this module stays free of any logging framework.
 */
public final class LogKeys {

    public static final String TENANT_ID = "tenantId";
    public static final String AGENT_ID = "agentId";
    public static final String CONVERSATION_ID = "conversationId";
    public static final String INVOCATION_ID = "invocationId";

    private LogKeys() {
    }
}
