package dev.cauce.api.support;

import dev.cauce.llm.speech.Transcriber;
import dev.cauce.llm.speech.Transcription;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stands in for a local Whisper in the cauce-api ITs that share {@code MessagingApiIT.FakeLlmConfig}
 * (ADR 0012): with it, the instance reads voice notes. It hears {@link #HEARD} unless a test sets
 * otherwise, and never calls a network.
 */
public class FakeTranscriber implements Transcriber {

    public static final String HEARD = "Quería mesa para cuatro el sábado";

    private volatile Transcription heard = new Transcription(HEARD, 4);
    private final AtomicInteger calls = new AtomicInteger();

    @Override
    public String id() {
        return "whisper";
    }

    @Override
    public String model() {
        return "fake-whisper";
    }

    @Override
    public Transcription transcribe(byte[] audio, String mediaType, String languageHint) {
        calls.incrementAndGet();
        return heard;
    }

    public int calls() {
        return calls.get();
    }

    public void reset() {
        heard = new Transcription(HEARD, 4);
        calls.set(0);
    }
}
