package dev.cauce.core.agent;

/**
 * Thrown when an operation references an agent that does not exist or is not visible
 * to the current tenant context. The two cases are deliberately not distinguished, so
 * the public API does not leak the existence of entities outside the caller's scope.
 */
public class AgentNotFoundException extends RuntimeException {

    public AgentNotFoundException(String message) {
        super(message);
    }
}
