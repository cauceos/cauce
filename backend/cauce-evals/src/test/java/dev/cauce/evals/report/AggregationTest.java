package dev.cauce.evals.report;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cauce.evals.check.CheckResult;
import dev.cauce.evals.check.CheckResult.Outcome;
import dev.cauce.evals.check.GeneralRules;
import dev.cauce.evals.scenario.Scenario;
import dev.cauce.evals.scenario.Scenario.Level;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AggregationTest {

    private static final Aggregation.Meta META = new Aggregation.Meta("2026-10-22T12:00Z", "abc", false, "anthropic/m",
            "openai/j", "calibrado", "f", 2, "batería completa", true, null, null, "2026-10-22");

    @Test
    void rates_areOverRepetitions_andOnlyCountedChecksDecide() {
        Scenario blocking = scenario("H4.7", Level.BLOCKING, "04-escalado");
        Scenario warning = scenario("H3.10", Level.WARNING, "03-reservas");
        List<ScenarioRun> runs = List.of(
                run(blocking, 1, CheckResult.pass("turno 1 · herramientas", ""),
                        new CheckResult("turno 1 · rúbrica", Outcome.NO_JUDGE, "")),
                run(blocking, 2, CheckResult.pass("turno 1 · herramientas", "")),
                run(warning, 1, CheckResult.pass("turno 1 · estado", "")),
                run(warning, 2, CheckResult.fail("turno 1 · estado", "")));

        RunSummary summary = Aggregation.summarize(runs, List.of(), 0.9, META);

        assertThat(summary.scenarios()).extracting(RunSummary.ScenarioRate::id).containsExactly("H3.10", "H4.7");
        assertThat(rate(summary, "H4.7")).isEqualTo(1.0);
        assertThat(rate(summary, "H3.10")).isEqualTo(0.5);
        assertThat(summary.blockingFailures()).isEmpty();
        assertThat(summary.warnings()).anyMatch(w -> w.startsWith("H3.10 50 %")).anyMatch(w -> w.equals("1 comprobaciones de rúbrica pendientes de juez: no cuentan en ninguna tasa"));
        assertThat(Aggregation.blocks(summary)).isFalse();
    }

    @Test
    void aBlockingScenario_orAGeneralRule_under100_blocks_andNotApplicableTurnsDoNotCount() {
        Scenario blocking = scenario("H3.5", Level.BLOCKING, "03-reservas");
        Scenario other = scenario("H1.1", Level.WARNING, "01-informacion");
        List<ScenarioRun> runs = List.of(
                run(blocking, 1, CheckResult.pass("turno 1 · estado", "")),
                run(blocking, 2, CheckResult.fail("turno 1 · estado", "")),
                run(other, 1, CheckResult.pass("turno 1 · herramientas", ""),
                        new CheckResult("turno 1 · " + GeneralRules.G1, Outcome.NOT_APPLICABLE, ""),
                        CheckResult.fail("turno 2 · " + GeneralRules.G1, "")));

        RunSummary summary = Aggregation.summarize(runs, List.of(), 0.9, META);

        assertThat(summary.blockingFailures()).containsExactly("H3.5 50 %", GeneralRules.G1 + " 0 %");
        assertThat(summary.generalRules()).containsEntry(GeneralRules.G1, 0.0);
        assertThat(Aggregation.blocks(summary)).isTrue();
    }

    @Test
    void theReport_showsTheChangeAgainstThePreviousRun_andTheFailingTranscript() {
        Scenario s = scenario("H1.5", Level.BLOCKING, "01-informacion");
        RunSummary before = Aggregation.summarize(List.of(run(s, 1, CheckResult.fail("turno 1 · respuesta", "x"))),
                List.of(), 0.9, META);
        List<ScenarioRun> runs = List.of(run(s, 1, CheckResult.pass("turno 1 · respuesta", "")),
                run(s, 2, CheckResult.fail("turno 1 · respuesta", "«se lo pregunto al dueño»")));
        RunSummary now = Aggregation.summarize(runs, List.of(), 0.9, META);

        String md = Report.markdown(now, Optional.of(before), runs);

        assertThat(md).contains("| H1.5 | bloqueante | 50 % (+50) | 2 | falla |")
                .contains("### H1.5 · repetición 2").contains("se lo pregunto al dueño")
                .contains("no aplicó en ningún turno");
        assertThat(Report.json(now)).contains("\"tasa_global\" : 0.5");
    }

    private static double rate(RunSummary summary, String id) {
        return summary.scenarios().stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow().rate();
    }

    private static ScenarioRun run(Scenario scenario, int repetition, CheckResult... results) {
        return new ScenarioRun(scenario, repetition, List.of(results), "Cliente: ...\nAgente: ...");
    }

    private static Scenario scenario(String id, Level level, String task) {
        return new Scenario(id, id, level, "bar-ejemplo", null, null, null,
                List.of(new Scenario.Turn("hola", null)), null, null, null, null, null, task);
    }
}
