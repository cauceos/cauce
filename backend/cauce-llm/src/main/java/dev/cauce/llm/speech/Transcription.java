package dev.cauce.llm.speech;

import java.util.Objects;
import java.util.OptionalInt;

/**
 * What a {@link Transcriber} heard: the text, verbatim as the provider returned it, and the length
 * of the audio in whole seconds (rounded up) when the provider reports it.
 */
public record Transcription(String text, Integer durationSeconds) {

    public Transcription {
        Objects.requireNonNull(text, "text");
        if (durationSeconds != null && durationSeconds < 0) {
            throw new IllegalArgumentException("durationSeconds must not be negative");
        }
    }

    /** The audio's length, when the provider reported it. */
    public OptionalInt duration() {
        return durationSeconds == null ? OptionalInt.empty() : OptionalInt.of(durationSeconds);
    }
}
