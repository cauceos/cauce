package dev.cauce.evals.run;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cauce.evals.voice.TranscriptionScore;
import dev.cauce.evals.voice.VoiceManifest;
import dev.cauce.evals.voice.VoiceReport;
import dev.cauce.llm.openai.OpenAiCompatibleTranscriber;
import dev.cauce.llm.speech.Transcription;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * How well the transcriber hears real voice notes (ADR 0012): reads the notes of the folder
 * {@code -Pevals.voz} names, by its {@code manifiesto.yaml}, transcribes each with the
 * OpenAI-compatible transcriber the instance would use — a local Whisper by default — and writes the
 * word error rate of each note and whether each critical datum was heard right. No application, no
 * model, no cost: only the transcriber.
 *
 * <pre>
 * ./gradlew :cauce-evals:eval -Pevals.voz=../../cauce-workspace/04_research/evals-voz/hosteleria
 *     [-Pevals.voz.url=http://localhost:8000/v1] [-Pevals.voz.modelo=Systran/faster-whisper-small]
 *     [-Pevals.voz.idioma=es]
 * </pre>
 *
 * The report quotes what the notes say, so it stays in {@code build/evals/}, never in the repo, and
 * nothing is written to the history.
 */
@EnabledIfSystemProperty(named = "evals.voz", matches = ".+")
class VoiceTranscriptionRun {

    @Test
    void run() throws Exception {
        Path root = Path.of(System.getProperty("evals.raiz"));
        // The scoring, not the prompt, is what the commit decides here: a warning is enough.
        GitState.announce(root).warning().ifPresent(warning -> System.out.printf("%n%s%n%n", warning));
        Path folder = root.resolve("backend").resolve(System.getProperty("evals.voz")).normalize();
        String url = System.getProperty("evals.voz.url", "http://localhost:8000/v1");
        String model = System.getProperty("evals.voz.modelo", "Systran/faster-whisper-small");
        String language = System.getProperty("evals.voz.idioma", "es");
        List<VoiceManifest.Note> notes = VoiceManifest.load(folder);
        OpenAiCompatibleTranscriber transcriber = new OpenAiCompatibleTranscriber("whisper", model, url, "",
                Duration.ofMinutes(5), HttpClient.newHttpClient(), language);

        List<VoiceReport.Result> results = new ArrayList<>();
        for (VoiceManifest.Note note : notes) {
            byte[] audio = Files.readAllBytes(folder.resolve(note.file()));
            long started = System.nanoTime(); // a local duration: how long the transcriber took
            Transcription heard = transcriber.transcribe(audio, mediaType(note.file()), null);
            double took = (System.nanoTime() - started) / 1e9;
            TranscriptionScore score = TranscriptionScore.of(note.transcript(), heard.text(), note.criticalData());
            results.add(new VoiceReport.Result(note, heard.text(), heard.durationSeconds(), took, score));
            System.out.printf(Locale.ROOT, "%s · %.1f s · %.1f %% · %s%n", note.id(), took, score.wordErrorRate() * 100,
                    score.criticalData());
        }

        String stamp = Instant.now().truncatedTo(ChronoUnit.MINUTES).toString().replace(":", "");
        Path out = root.resolve("backend/cauce-evals/build/evals").resolve("voz-" + stamp);
        Files.createDirectories(out);
        String title = "Transcripción de notas de voz · " + model + " · " + stamp;
        Files.writeString(out.resolve("informe.md"), VoiceReport.markdown(title, results), StandardCharsets.UTF_8);
        Files.writeString(out.resolve("resultados.csv"), VoiceReport.csv(results), StandardCharsets.UTF_8);
        int[] critical = VoiceReport.criticalDataRight(results);
        System.out.printf(Locale.ROOT, "%nError de palabras: %.1f %% · datos críticos: %d de %d · %.1f s por nota"
                        + " · informe: %s%n", VoiceReport.overallWordErrorRate(results) * 100, critical[0], critical[1],
                VoiceReport.meanTranscriptionSeconds(results), out.resolve("informe.md"));
        assertThat(results).hasSameSizeAs(notes);
    }

    private static String mediaType(String file) {
        String name = file.toLowerCase(Locale.ROOT);
        if (name.endsWith(".ogg") || name.endsWith(".oga") || name.endsWith(".opus")) {
            return "audio/ogg";
        }
        if (name.endsWith(".mp3")) {
            return "audio/mpeg";
        }
        if (name.endsWith(".m4a") || name.endsWith(".aac")) {
            return "audio/mp4";
        }
        if (name.endsWith(".wav")) {
            return "audio/wav";
        }
        return "application/octet-stream";
    }
}
