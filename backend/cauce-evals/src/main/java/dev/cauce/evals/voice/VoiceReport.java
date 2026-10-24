package dev.cauce.evals.voice;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The report of a transcription run over a manifest's notes (ADR 0012): one row per note — its word
 * error rate and each critical datum heard right or not — and the totals. The overall rate weighs
 * each note by its words, as the rate of all the words together. Written next to the run, outside
 * the repo: it quotes what the notes say.
 */
public final class VoiceReport {

    /**
     * One note's result: what the transcriber heard, how long the note was, how long transcribing it
     * took, and its score.
     */
    public record Result(VoiceManifest.Note note, String heard, Integer seconds, double transcriptionSeconds,
                         TranscriptionScore score) {
    }

    /** The mean time it took to transcribe a note. */
    public static double meanTranscriptionSeconds(List<Result> results) {
        return results.stream().mapToDouble(Result::transcriptionSeconds).average().orElse(0);
    }

    private VoiceReport() {
    }

    /** The word error rate of all the notes' words together. */
    public static double overallWordErrorRate(List<Result> results) {
        double errors = results.stream().mapToDouble(r -> r.score().wordErrorRate() * r.score().expectedWords()).sum();
        int words = results.stream().mapToInt(r -> r.score().expectedWords()).sum();
        return words == 0 ? 0 : errors / words;
    }

    /** How many critical data were heard right, of how many the notes name. */
    public static int[] criticalDataRight(List<Result> results) {
        int right = 0;
        int all = 0;
        for (Result result : results) {
            for (boolean heard : result.score().criticalData().values()) {
                all++;
                right += heard ? 1 : 0;
            }
        }
        return new int[] {right, all};
    }

    public static String markdown(String title, List<Result> results) {
        int[] critical = criticalDataRight(results);
        StringBuilder md = new StringBuilder("# " + title + "\n\n");
        md.append(String.format(Locale.ROOT, "- Notas: %d · tasa de error de palabras: **%.1f %%**%n", results.size(),
                overallWordErrorRate(results) * 100));
        md.append(String.format(Locale.ROOT, "- Datos críticos bien oídos: **%d de %d**%n", critical[0], critical[1]));
        md.append(String.format(Locale.ROOT, "- Tiempo de transcripción por nota: %.1f s de media%n%n",
                meanTranscriptionSeconds(results)));
        md.append("| Nota | Segundos | Transcrita en | Error de palabras | Datos críticos |\n|---|---|---|---|---|\n");
        for (Result result : results) {
            String data = result.score().criticalData().isEmpty() ? "—"
                    : result.score().criticalData().entrySet().stream()
                            .map(d -> "«" + d.getKey() + "»" + (d.getValue() ? " ✓" : " ✗"))
                            .collect(Collectors.joining(", "));
            md.append(String.format(Locale.ROOT, "| %s | %s | %.1f s | %.1f %% | %s |%n", result.note().id(),
                    result.seconds() == null ? "?" : result.seconds(), result.transcriptionSeconds(),
                    result.score().wordErrorRate() * 100, data));
        }
        md.append("\n## Lo que se dijo y lo que se oyó\n");
        for (Result result : results) {
            md.append("\n**").append(result.note().id()).append("**")
                    .append(result.note().notes() == null ? "" : " · " + result.note().notes()).append("\n\n")
                    .append("- Dicho: ").append(result.note().transcript()).append("\n")
                    .append("- Oído: ").append(result.heard()).append("\n");
        }
        return md.toString();
    }

    /**
     * One line per note, with a header: what a later comparison reads back. The critical data heard
     * wrong are listed, separated by {@code |}.
     */
    public static String csv(List<Result> results) {
        StringBuilder csv = new StringBuilder("id,seconds,transcription_seconds,expected_words,word_error_rate,"
                + "critical_right,critical_total,critical_missed\n");
        for (Result result : results) {
            long right = result.score().criticalData().values().stream().filter(Boolean::booleanValue).count();
            String missed = result.score().criticalData().entrySet().stream().filter(d -> !d.getValue())
                    .map(Map.Entry::getKey).collect(Collectors.joining("|"));
            csv.append(result.note().id()).append(',').append(result.seconds() == null ? "" : result.seconds())
                    .append(',').append(String.format(Locale.ROOT, "%.2f", result.transcriptionSeconds()))
                    .append(',').append(result.score().expectedWords()).append(',')
                    .append(String.format(Locale.ROOT, "%.4f", result.score().wordErrorRate())).append(',')
                    .append(right).append(',').append(result.score().criticalData().size()).append(',')
                    .append(missed).append('\n');
        }
        return csv.toString();
    }
}
