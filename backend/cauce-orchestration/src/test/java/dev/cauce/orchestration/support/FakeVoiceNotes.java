package dev.cauce.orchestration.support;

import dev.cauce.core.message.MessagePart;
import dev.cauce.llm.speech.Transcriber;
import dev.cauce.llm.speech.Transcription;
import dev.cauce.orchestration.voice.VoiceNoteSource;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A scriptable stand-in for both ends of a voice note (ADR 0012): the channel that hands over its
 * bytes and the transcriber that hears them. Shared by the orchestration ITs through
 * {@link MockLlmProviderTestConfig}; a test sets what is heard with {@link #hear}.
 */
public class FakeVoiceNotes implements Transcriber, VoiceNoteSource {

    public static final byte[] AUDIO = "OggS-fake-voice-note".getBytes(StandardCharsets.UTF_8);

    private volatile Transcription heard = new Transcription("Hola", 1);
    private final AtomicInteger transcriptions = new AtomicInteger();

    /** What the next transcriptions hear. */
    public void hear(String text, int seconds) {
        this.heard = new Transcription(text, seconds);
    }

    /** How many transcriptions were made since {@link #reset()}. */
    public int transcriptions() {
        return transcriptions.get();
    }

    public void reset() {
        heard = new Transcription("Hola", 1);
        transcriptions.set(0);
    }

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
        transcriptions.incrementAndGet();
        return heard;
    }

    @Override
    public byte[] fetch(UUID channelConfigId, MessagePart.Audio note, long maxBytes) {
        return AUDIO;
    }
}
