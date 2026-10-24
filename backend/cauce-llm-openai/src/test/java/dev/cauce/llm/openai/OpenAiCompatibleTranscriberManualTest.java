package dev.cauce.llm.openai;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cauce.llm.speech.Transcription;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Manual smoke test against a local Whisper (speaches) on {@code http://localhost:18000}, with the
 * model already downloaded ({@code POST /v1/models/Systran/faster-whisper-small}). Runs only when
 * {@code CAUCE_MANUAL_WHISPER_AUDIO} names an audio file (Ogg Opus, as Telegram sends): it needs the
 * server and the file, so it is never part of a normal build.
 */
@EnabledIfEnvironmentVariable(named = "CAUCE_MANUAL_WHISPER_AUDIO", matches = ".+")
class OpenAiCompatibleTranscriberManualTest {

    @Test
    void transcribesARealVoiceNote() throws Exception {
        byte[] audio = Files.readAllBytes(Path.of(System.getenv("CAUCE_MANUAL_WHISPER_AUDIO")));
        OpenAiCompatibleTranscriber transcriber = new OpenAiCompatibleTranscriber("whisper",
                "Systran/faster-whisper-small", "http://localhost:18000/v1", "", Duration.ofSeconds(120),
                HttpClient.newHttpClient(), "es");

        Transcription transcription = transcriber.transcribe(audio, "audio/ogg", null);

        System.out.println("Whisper heard (" + transcription.durationSeconds() + " s): " + transcription.text());
        assertThat(transcription.text()).isNotBlank();
        assertThat(transcription.duration()).isPresent();
    }
}
