package dev.cauce.evals.report;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cauce.evals.check.CheckResult;
import dev.cauce.evals.check.CheckResult.Outcome;
import dev.cauce.evals.check.GeneralRules;
import dev.cauce.evals.cost.PriceTable;
import dev.cauce.evals.scenario.Scenario;
import dev.cauce.evals.scenario.Scenario.Level;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AggregationTest {

    private static final Aggregation.Meta META = new Aggregation.Meta("2026-10-22T12:00Z", "abc", false, "anthropic/m",
            "openai/j", "calibrado", "f", 2, "batería completa", true, null, null, "2026-10-22", null);

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

    @Test
    void theConsoleSummary_hasOneLinePerScenario_andTheChecksThatDidNotAlwaysPass_withoutTranscripts() {
        Scenario s = scenario("H3.4", Level.BLOCKING, "03-reservas");
        List<ScenarioRun> runs = List.of(run(s, 1, CheckResult.pass("turno 1 · herramientas", ""),
                        CheckResult.fail("turno 2 · herramientas", "faltan [confirm_booking]")),
                run(s, 2, CheckResult.pass("turno 1 · herramientas", ""), CheckResult.pass("turno 2 · herramientas", "")));

        String console = Report.console(Aggregation.summarize(runs, List.of(), 0.9, META));

        assertThat(console).contains("H3.4      bloqueante    50 %  2     falla\n")
                .contains("  H3.4 · turno 2 · herramientas: 50 %").doesNotContain("turno 1 · herramientas:")
                .contains("Bloqueantes por debajo del 100 %: H3.4 50 %").doesNotContain("Cliente:");
    }

    @Test
    void theConsoleSummary_listsAScenarioCoveredByAnIt_whichHasNoChecks() {
        Scenario s = scenario("H3.1", Level.BLOCKING, "03-reservas");
        Scenario covered = new Scenario("H3.7", "H3.7", Level.BLOCKING, "bar-ejemplo", null, null, null, null, null,
                null, null, "BookingIT#neverOverbook", null, "03-reservas");

        String console = Report.console(Aggregation.summarize(
                List.of(run(s, 1, CheckResult.pass("turno 1 · herramientas", ""))), List.of(covered), 0.9, META));

        assertThat(console).contains("H3.7      bloqueante       —  0     cubierto por BookingIT#neverOverbook\n")
                .contains("Sin bloqueantes fallidos.");
    }

    @Test
    void anExecutionFailure_keepsItsRepetitionOutOfTheRates_andTheReportNamesItsCause() {
        // H3.10 of the 2026-10-23 baseline: turn 2 got the fallback reply after a provider 400.
        Scenario s = scenario("H3.10", Level.WARNING, "03-reservas");
        String cause = "FAILED · LLM_ERROR · HTTP 400: This model does not support assistant message prefill.";
        List<ScenarioRun> runs = List.of(
                run(s, 1, CheckResult.pass("turno 1 · herramientas", ""), CheckResult.pass("turno 2 · herramientas", "")),
                run(s, 2, CheckResult.pass("turno 1 · herramientas", ""),
                        new CheckResult("turno 2 · fallo de ejecución", Outcome.EXECUTION_FAILURE, cause)));

        RunSummary summary = Aggregation.summarize(runs, List.of(), 0.9, META);

        assertThat(rate(summary, "H3.10")).isEqualTo(1.0);
        assertThat(summary.overallRate()).isEqualTo(1.0);
        assertThat(summary.warnings()).contains(
                "H3.10 · repetición 2 · turno 2 · fallo de ejecución, fuera de la tasa del agente: " + cause);
        assertThat(Report.console(summary)).contains("fuera de la tasa del agente: " + cause);
        assertThat(Report.markdown(summary, Optional.empty(), runs)).contains("### H3.10 · repetición 2")
                .contains("EXECUTION_FAILURE · turno 2 · fallo de ejecución: " + cause);
    }

    @Test
    void theReports_giveTheInputTokensPerModelTurn_whatR1IsMeantToLower() {
        Aggregation.Meta meta = new Aggregation.Meta("2026-10-23T08:00Z", "abc", false, "anthropic/m", "ninguno",
                "pendiente de juez", "f", 5, "batería completa · R1: consultas pasadas omitidas", true,
                new RunSummary.Tokens(26_000, 20_000, 1_000, 0, 500, 2, 0, 0), 0.01, "2026-10-22", null);
        Scenario s = scenario("G3", Level.WARNING, "00-anclaje");
        List<ScenarioRun> runs = List.of(run(s, 1, CheckResult.pass("turno 1 · herramientas", "")));
        RunSummary summary = Aggregation.summarize(runs, List.of(), 0.9, meta);

        assertThat(Report.console(summary)).contains("(13000 de entrada por turno)").contains("R1: consultas pasadas omitidas");
        assertThat(Report.markdown(summary, Optional.empty(), runs)).contains("2 turnos (13000 de entrada por turno)");
    }

    @Test
    void eachScenario_sumsTheTokensOfItsRepetitions_andPricesThem() {
        PriceTable.Price price = new PriceTable.Price("anthropic", "m", 2.0, 2.5, null, 0.2, 10.0, "2026-10-22");
        Aggregation.Meta meta = new Aggregation.Meta("2026-10-23T08:00Z", "abc", false, "anthropic/m", "ninguno",
                "pendiente de juez", "f", 2, "batería completa", true, null, null, "2026-10-22", price);
        Scenario s = scenario("H3.1", Level.WARNING, "03-reservas");
        Scenario digest = scenario("H5.1", Level.WARNING, "05-resumen");
        List<ScenarioRun> runs = List.of(
                new ScenarioRun(s, 1, List.of(CheckResult.pass("turno 1 · herramientas", "")), "",
                        new RunSummary.ScenarioTokens(600_000, 500_000, 50_000, 0, 10_000, 2)),
                new ScenarioRun(s, 2, List.of(CheckResult.pass("turno 1 · herramientas", "")), "",
                        new RunSummary.ScenarioTokens(400_000, 300_000, 50_000, 0, 10_000, 2)),
                run(digest, 1, CheckResult.pass("resumen", "")));

        RunSummary summary = Aggregation.summarize(runs, List.of(), 0.9, meta);

        RunSummary.ScenarioRate rate = summary.scenarios().stream().filter(r -> r.id().equals("H3.1")).findFirst()
                .orElseThrow();
        assertThat(rate.tokens()).isEqualTo(new RunSummary.ScenarioTokens(1_000_000, 800_000, 100_000, 0, 20_000, 4));
        // 100k uncached × 2 + 800k read × 0.2 + 100k written × 2.5 + 20k out × 10, per million.
        assertThat(rate.costUsd()).isCloseTo(0.2 + 0.16 + 0.25 + 0.2, org.assertj.core.data.Offset.offset(1e-9));
        // One repetition is one conversation: 0.81 over two.
        assertThat(Report.markdown(summary, Optional.empty(), runs)).containsAnyOf("| ok | 0.4050 US$ |",
                "| ok | 0,4050 US$ |");
        RunSummary.ScenarioRate summaryOnly = summary.scenarios().stream().filter(r -> r.id().equals("H5.1"))
                .findFirst().orElseThrow();
        assertThat(summaryOnly.tokens()).isNull();
        assertThat(summaryOnly.costUsd()).isNull();
        assertThat(Report.json(summary)).contains("\"turnos_modelo\" : 4");
    }

    @Test
    void aRunIsComparedWithTheLastOfItsModel_orTheLastOfAnyTheFirstTime(@org.junit.jupiter.api.io.TempDir
                                                                         java.nio.file.Path dir) throws Exception {
        java.nio.file.Files.writeString(dir.resolve("2026-10-23T1138Z-claude-sonnet-5-5.json"), """
                {"fecha": "2026-10-23T11:38Z", "modelo": "anthropic/claude-sonnet-5-5", "n": 5, "completa": true,
                 "tasa_global": 0.99, "arbol_con_cambios": false, "escenarios": []}
                """);
        java.nio.file.Files.writeString(dir.resolve("2026-10-22T1340Z-claude-haiku-5-5.json"), """
                {"fecha": "2026-10-22T13:40Z", "modelo": "anthropic/claude-haiku-5-5", "n": 5, "completa": true,
                 "tasa_global": 0.9, "arbol_con_cambios": false, "escenarios": []}
                """);

        assertThat(Report.previous(dir, "anthropic/claude-haiku-5-5")).get()
                .extracting(RunSummary::date).isEqualTo("2026-10-22T13:40Z");
        assertThat(Report.previous(dir, "anthropic/claude-opus-5-5")).get()
                .extracting(RunSummary::date).isEqualTo("2026-10-23T11:38Z");
        Aggregation.Meta haiku = new Aggregation.Meta("2026-10-24T09:00Z", "abc", false, "anthropic/claude-opus-5-5",
                "ninguno", "pendiente de juez", "f", 5, "batería completa", true, null, null, "2026-10-23", null);
        List<ScenarioRun> runs = List.of(run(scenario("G1", Level.BLOCKING, "00-anclaje"), 1,
                CheckResult.pass("turno 1 · herramientas", "")));
        assertThat(Report.markdown(Aggregation.summarize(runs, List.of(), 0.9, haiku),
                Report.previous(dir, "anthropic/claude-opus-5-5"), runs))
                .contains("- Comparada con `2026-10-23T11:38Z` · `anthropic/claude-sonnet-5-5` (otro modelo)");
    }

    @Test
    void aSummaryWrittenBeforeTheScenarioTokens_stillReads(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        java.nio.file.Files.writeString(dir.resolve("2026-10-22T1340Z-m.json"), """
                {"fecha": "2026-10-22T13:40Z", "n": 1, "completa": true, "tasa_global": 1.0, "arbol_con_cambios": false,
                 "escenarios": [{"id": "G1", "tarea": "00-anclaje", "nivel": "bloqueante", "tasa": 1.0,
                                 "repeticiones": 1, "sin_juez": 0, "estado": "ok"}]}
                """);

        RunSummary previous = Report.previous(dir).orElseThrow();

        assertThat(previous.scenarios().get(0).tokens()).isNull();
        assertThat(previous.scenarios().get(0).costUsd()).isNull();
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
