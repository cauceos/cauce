package dev.cauce.orchestration.exception;

/**
 * Thrown when a voice note must be transcribed and the instance cannot: no transcriber configured,
 * or no channel able to hand over the note's bytes. The worker fails the invocation as
 * {@code AGENT_CONFIGURATION}: the instance's setup to fix, never a provider outage.
 */
public class TranscriptionNotAvailableException extends RuntimeException {

    public TranscriptionNotAvailableException(String message) {
        super(message);
    }
}
