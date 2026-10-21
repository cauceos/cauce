package dev.cauce.core.agent;

/**
 * Thrown when an agent is granted a tool that no registered tool answers to (ADR 0005, C4):
 * every name in an agent's tool list must exist in the tool catalog when the list is set.
 */
public class UnknownToolException extends RuntimeException {

    public UnknownToolException(String message) {
        super(message);
    }
}
