package dev.cauce.evals.report;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cauce.evals.check.CheckResult;
import dev.cauce.evals.check.CheckResult.Outcome;
import dev.cauce.evals.scenario.Scenario;
import dev.cauce.evals.scenario.Scenario.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunValidityTest {

    private static final String REFUSED = "FAILED · LLM_ERROR · dev.cauce.llm.exception.LlmAuthenticationException: "
            + "[provider=anthropic, model=claude-sonnet-5-5] HTTP 401: invalid x-api-key";
    private static final Aggregation.Meta META = new Aggregation.Meta("2026-10-23T11:09Z", "abc", false,
            "anthropic/m", "ninguno", "pendiente de juez", "f", 5, "batería completa", true, null, null, "2026-10-22",
            null);

    @Test
    void aRunWhereNoTurnExecuted_isInvalid_andBlocks_thoughItsRatesSayNothingFailed() {
        // The 2026-10-23T1109Z run: every turn refused with a 401, only the daily summaries passed.
        Scenario g1 = scenario("G1", Level.BLOCKING);
        Scenario h5 = scenario("H5.1", Level.WARNING);
        List<ScenarioRun> runs = new ArrayList<>();
        for (int rep = 1; rep <= 5; rep++) {
            runs.add(failed(g1, rep));
        }
        runs.add(new ScenarioRun(h5, 1, List.of(CheckResult.pass("resumen", "")), ""));

        RunSummary summary = Aggregation.summarize(runs, List.of(), 0.9, 0.10, META);

        assertThat(summary.blockingFailures()).isEmpty();
        assertThat(summary.invalidity()).containsExactly(
                "5 de 6 repeticiones con fallo de ejecución (83 %), por encima del umbral de 10 %",
                "G1: ninguna repetición válida (5 de 5 con fallo de ejecución)");
        assertThat(summary.valid()).isFalse();
        assertThat(Aggregation.blocks(summary)).isTrue();
        assertThat(Report.console(summary)).contains("EJECUCIÓN INVÁLIDA: G1: ninguna repetición válida");
        assertThat(Report.markdown(summary, java.util.Optional.empty(), runs))
                .contains("**Ejecución inválida: no dice nada del agente y no va al historial.**");
    }

    @Test
    void aScenarioWithNoValidRepetition_isInvalid_evenUnderTheThreshold() {
        List<ScenarioRun> runs = new ArrayList<>();
        for (int i = 1; i <= 19; i++) {
            runs.add(passed(scenario("H" + i, Level.WARNING)));
        }
        runs.add(failed(scenario("H3.13", Level.WARNING), 1));

        assertThat(RunValidity.reasons(runs, 0.10))
                .containsExactly("H3.13: ninguna repetición válida (1 de 1 con fallo de ejecución)");
    }

    @Test
    void executionFailuresOverTheThreshold_makeTheRunInvalid_andAtItDoNot() {
        Scenario s = scenario("H3.1", Level.WARNING);
        List<ScenarioRun> atThreshold = new ArrayList<>();
        for (int rep = 1; rep <= 9; rep++) {
            atThreshold.add(passed(s));
        }
        atThreshold.add(failed(s, 10));
        List<ScenarioRun> over = new ArrayList<>(atThreshold);
        over.set(0, failed(s, 1));

        assertThat(RunValidity.reasons(atThreshold, 0.10)).isEmpty();
        assertThat(RunValidity.reasons(over, 0.10)).containsExactly(
                "2 de 10 repeticiones con fallo de ejecución (20 %), por encima del umbral de 10 %");
    }

    @Test
    void aRefusedKey_isRecognised_andAnotherFailureIsNot() {
        assertThat(RunValidity.refusedCredentials(REFUSED)).isTrue();
        assertThat(RunValidity.refusedCredentials("FAILED · EMPTY_REPLY · empty")).isFalse();
        assertThat(RunValidity.refusedCredentials(null)).isFalse();
    }

    @Test
    void aSummaryWrittenBeforeValidity_readsAsValid(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("2026-10-22T1340Z-m.json"), """
                {"fecha": "2026-10-22T13:40Z", "n": 1, "completa": true, "tasa_global": 1.0,
                 "arbol_con_cambios": false, "escenarios": []}
                """);

        RunSummary previous = Report.previous(dir).orElseThrow();

        assertThat(previous.invalidity()).isEmpty();
        assertThat(previous.valid()).isTrue();
    }

    private static ScenarioRun failed(Scenario scenario, int rep) {
        return new ScenarioRun(scenario, rep, List.of(new CheckResult("turno 1 · fallo de ejecución",
                Outcome.EXECUTION_FAILURE, REFUSED)), "");
    }

    private static ScenarioRun passed(Scenario scenario) {
        return new ScenarioRun(scenario, 1, List.of(CheckResult.pass("turno 1 · herramientas", "")), "");
    }

    private static Scenario scenario(String id, Level level) {
        return new Scenario(id, id, level, "bar-ejemplo", null, null, null,
                List.of(new Scenario.Turn("hola", null)), null, null, null, null, null, "00-anclaje");
    }
}
