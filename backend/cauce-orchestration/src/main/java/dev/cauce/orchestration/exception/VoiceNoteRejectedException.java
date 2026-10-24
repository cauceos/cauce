package dev.cauce.orchestration.exception;

/**
 * Thrown when a person's voice note cannot be read (ADR 0012, V5): longer or larger than the
 * limits, or nothing intelligible in it. The worker fails the invocation as
 * {@code VOICE_NOTE_REJECTED}, and the person is asked to write it or to send a shorter one; the
 * model is not called.
 */
public class VoiceNoteRejectedException extends RuntimeException {

    public VoiceNoteRejectedException(String message) {
        super(message);
    }
}
