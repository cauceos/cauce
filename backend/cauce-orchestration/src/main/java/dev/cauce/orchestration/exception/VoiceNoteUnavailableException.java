package dev.cauce.orchestration.exception;

/**
 * Thrown when the channel did not hand over a voice note's bytes (ADR 0012, V1). A transient
 * failure — the provider unreachable, timing out, or answering 5xx or 429 — is retried by the
 * worker with backoff, as a transient model error is, and each attempt fetches the note anew (on
 * WhatsApp, with a new download URL); the retries exhausted, the person gets the fallback reply.
 * A permanent one — the provider no longer knows the note — fails as a voice note that could not
 * be read.
 */
public class VoiceNoteUnavailableException extends RuntimeException {

    private final boolean transientFailure;

    public VoiceNoteUnavailableException(String message, boolean transientFailure, Throwable cause) {
        super(message, cause);
        this.transientFailure = transientFailure;
    }

    /** Whether a later attempt may get the bytes. */
    public boolean isTransient() {
        return transientFailure;
    }
}
