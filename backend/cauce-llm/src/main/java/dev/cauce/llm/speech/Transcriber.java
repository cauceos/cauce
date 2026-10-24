package dev.cauce.llm.speech;

/**
 * The transcription SPI (ADR 0012, V3): a voice note's bytes in, what it says out. Provider-neutral
 * like {@link dev.cauce.llm.spi.LlmProvider}: the core and the orchestrator never name a provider,
 * and an adapter registers one bean when it is configured. At most one per instance; with none,
 * voice notes are answered as a message Cauce does not read (ADR 0006, W5).
 *
 * <p>Failures are the LLM SPI's exceptions ({@code dev.cauce.llm.exception}), carrying
 * {@link #id()} and {@link #model()}: a transcriber is a provider call like a model's, and the
 * worker retries or fails it by the same rules.
 */
public interface Transcriber {

    /** The stable provider id its usage and price name (e.g. {@code whisper}). */
    String id();

    /** The model it transcribes with, as its usage and price name it. */
    String model();

    /**
     * Transcribes {@code audio}, of media type {@code mediaType}.
     *
     * @param languageHint the expected language as an ISO 639-1 code, or {@code null} to detect it
     * @throws dev.cauce.llm.exception.LlmProviderException if the provider fails
     */
    Transcription transcribe(byte[] audio, String mediaType, String languageHint);
}
