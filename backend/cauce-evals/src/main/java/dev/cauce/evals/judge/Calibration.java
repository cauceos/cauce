package dev.cauce.evals.judge;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.cauce.evals.judge.Judge.Verdict;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Calibrates a judge before it judges anything (ADR 0009, E6): every labelled case of the pack's
 * {@code calibracion/} is judged {@value #REPETITIONS} times, and the judge is accepted only if
 * its majority verdict matches the label on every case. Cases still labelled {@code pendiente}
 * do not count; with no labelled case the judge is uncalibrated, which is not accepted either.
 */
public final class Calibration {

    static final int REPETITIONS = 3;

    /** One real turn with the pack owner's verdict on a rubric. */
    public record LabelledCase(
            @JsonProperty("id") String id,
            @JsonProperty("origen") String origin,
            @JsonProperty("escenario") String scenario,
            @JsonProperty("cliente") String message,
            @JsonProperty("herramientas") List<String> tools,
            @JsonProperty("respuesta") String reply,
            @JsonProperty("rubrica") String rubric,
            @JsonProperty("etiqueta") String label,
            @JsonProperty("motivo") String reason) {

        public boolean labelled() {
            return "cumple".equals(label) || "no_cumple".equals(label);
        }

        Verdict expected() {
            return "cumple".equals(label) ? Verdict.MEETS : Verdict.FAILS;
        }
    }

    /** The calibration's conclusion. */
    public enum Status {
        ACCEPTED,
        /** No labelled case: the rubrics are reported "pendiente de juez". */
        UNCALIBRATED,
        /** It disagreed with a label: the rubrics are reported "pendiente de juez". */
        REJECTED
    }

    public record Result(Status status, int labelledCases, List<String> disagreements) {

        public Result {
            disagreements = List.copyOf(disagreements);
        }

        public boolean accepted() {
            return status == Status.ACCEPTED;
        }
    }

    private Calibration() {
    }

    /** Every case of the {@code *.yaml} files in {@code directory}; none when it does not exist. */
    public static List<LabelledCase> load(Path directory) {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        ObjectMapper yaml = new ObjectMapper(new YAMLFactory()).enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        List<LabelledCase> cases = new ArrayList<>();
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".yaml")).sorted().toList()) {
                cases.addAll(yaml.readValue(file.toFile(), new TypeReference<List<LabelledCase>>() { }));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the calibration cases in " + directory, e);
        }
        return cases;
    }

    /** Calibrates {@code judge} on the labelled {@code cases}. */
    public static Result calibrate(Judge judge, List<LabelledCase> cases) {
        List<LabelledCase> labelled = cases.stream().filter(LabelledCase::labelled).toList();
        if (labelled.isEmpty()) {
            return new Result(Status.UNCALIBRATED, 0, List.of());
        }
        List<String> disagreements = new ArrayList<>();
        for (LabelledCase c : labelled) {
            Judge.Case turn = new Judge.Case(c.message(), c.tools() == null ? List.of() : c.tools(), c.reply(),
                    c.rubric());
            int agree = 0;
            for (int i = 0; i < REPETITIONS; i++) {
                if (judge.judge(turn).verdict() == c.expected()) {
                    agree++;
                }
            }
            if (agree * 2 <= REPETITIONS) {
                disagreements.add(c.id() + " (etiqueta " + c.label() + ", de acuerdo " + agree + "/" + REPETITIONS + ")");
            }
        }
        return new Result(disagreements.isEmpty() ? Status.ACCEPTED : Status.REJECTED, labelled.size(), disagreements);
    }
}
