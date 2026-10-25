package dev.cauce.evals.check;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cauce.evals.check.CheckResult.Outcome;
import dev.cauce.evals.check.TurnTrace.ObservedBooking;
import dev.cauce.evals.check.TurnTrace.ObservedNotice;
import dev.cauce.evals.check.TurnTrace.ObservedState;
import dev.cauce.evals.check.TurnTrace.ToolUse;
import dev.cauce.evals.scenario.RelativeDates;
import dev.cauce.evals.scenario.ScenarioLoader;
import dev.cauce.evals.scenario.Scenario.Expectation;
import dev.cauce.evals.scenario.Scenario.ExpectedState;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CheckerTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 22); // a Thursday
    private final Checker checker = new Checker(Map.of("r", "DCK66M"), spec -> RelativeDates.resolve(spec, TODAY));

    /** The change of the second Telegram test: same code, 6 people, confirmed. */
    private final TurnTrace change = new TurnTrace("Al final seremos 6",
            List.of(new ToolUse("list_my_bookings", Map.of(), "{}"),
                    new ToolUse("propose_booking_change", Map.of("code", "DCK66M", "party_size", 6), "{}")),
            "El código sigue siendo **DCK66M**.", "El código sigue siendo <b>DCK66M</b>.", null,
            new ObservedState(List.of(
                    new ObservedBooking("CONFIRMED", LocalDate.of(2026, 10, 24), "cena", "21:30", 4, "DCK66M", "Ana"),
                    new ObservedBooking("HELD", LocalDate.of(2026, 10, 24), "cena", "21:30", 6, "DCK66M", "Ana")),
                    List.of(), 0, List.of(new ObservedNotice("HANDOFF", "NOT_SENT", "no_owner_recipient", "x")), 0, null));

    @Test
    void theTrace_isCheckedForCallsOrderForbiddenToolsAndArguments_withVariablesResolved() {
        Expectation e = expectation(List.of("propose_booking_change"), List.of("list_my_bookings", "propose_booking_change"),
                List.of("confirm_booking"), Map.of("propose_booking_change", Map.of("code", "$r.codigo", "party_size", 6)),
                List.of("$r.codigo"), List.of("código nuevo"), null);

        List<CheckResult> results = checker.check("turno 1", e, change);

        assertThat(results).extracting(CheckResult::outcome).containsOnly(Outcome.PASS);
        assertThat(results).hasSize(6);
    }

    @Test
    void aWrongOrder_aForbiddenCall_andWrongArguments_fail() {
        Expectation e = expectation(null, List.of("propose_booking_change", "list_my_bookings"),
                List.of("list_my_bookings"), Map.of("propose_booking_change", Map.of("code", "OTRO99")), null, null,
                null);

        assertThat(checker.check("turno 1", e, change)).extracting(CheckResult::outcome)
                .containsExactly(Outcome.FAIL, Outcome.FAIL, Outcome.FAIL);
    }

    @Test
    void aDateArgument_inBraces_isTheRelativeDate_asTheToolTakesIt() {
        TurnTrace asked = new TurnTrace("Mesa para dos mañana",
                List.of(new ToolUse("check_availability", Map.of("date", "2026-10-23"), "{}")), "", null, null,
                change.state());

        assertThat(checker.check("turno 1", expectation(null, null, null,
                Map.of("check_availability", Map.of("date", "{+1}")), null, null, null), asked))
                .extracting(CheckResult::outcome).containsExactly(Outcome.PASS);
        assertThat(checker.check("turno 1", expectation(null, null, null,
                Map.of("check_availability", Map.of("date", "{proximo_sabado}")), null, null, null), asked))
                .extracting(CheckResult::outcome).containsExactly(Outcome.FAIL);
    }

    @Test
    void aReplyText_withARelativeDate_isTheDateWrittenOut() {
        TurnTrace answered = new TurnTrace("¿Mañana abrís a mediodía?", List.of(),
                "Mañana, viernes 23, abrimos a las 12:00.", null, null, change.state());

        assertThat(checker.check("turno 1", expectation(null, null, null, null, List.of("{+1:dia_semana}"),
                List.of("{+2:dia_semana}"), null), answered))
                .extracting(CheckResult::outcome).containsExactly(Outcome.PASS, Outcome.PASS);
        assertThat(checker.check("turno 1", expectation(null, null, null, null, List.of("{+0:dia_semana}"), null,
                null), answered))
                .extracting(CheckResult::outcome).containsExactly(Outcome.FAIL);
    }

    @Test
    void theState_isMatchedInItsVocabulary_countsByStatus_andRelativeDates() {
        ExpectedState state = new ExpectedState(Map.of("CONFIRMED", 1, "HELD", 1),
                Map.of("estado", "HELD", "personas", 6, "codigo", "$r.codigo", "fecha", "proximo_sabado", "hora", "21:30"),
                Map.of("OPEN", 0), null, 0, Map.of("HANDOFF", 1),
                Map.of("tipo", "HANDOFF", "estado", "NOT_SENT", "motivo", "no_owner_recipient"), null);

        List<CheckResult> results = checker.check("turno 1", expectation(null, null, null, null, null, null, state), change);

        assertThat(results).extracting(CheckResult::outcome).containsOnly(Outcome.PASS);
        ExpectedState wrong = new ExpectedState(Map.of("CONFIRMED", 2), Map.of("personas", 8), null, null, null, null,
                null, null);
        assertThat(checker.check("turno 1", expectation(null, null, null, null, null, null, wrong), change))
                .extracting(CheckResult::outcome).containsOnly(Outcome.FAIL);
    }

    @Test
    void aReplyContains_anyOfItsAlternatives() {
        TurnTrace tart = new TurnTrace("¿Qué alérgenos tiene la tarta de queso?", List.of(),
                "El bar no ha declarado los alérgenos, así que no me consta qué lleva.", null, null, change.state());
        Expectation either = expectation(null, null, null, null, List.of("no consta | no me consta"), null, null);
        Expectation neither = expectation(null, null, null, null, List.of("no consta | sin declarar"), null, null);

        assertThat(checker.check("turno 1", either, tart)).extracting(CheckResult::outcome).containsExactly(Outcome.PASS);
        assertThat(checker.check("turno 1", neither, tart)).extracting(CheckResult::outcome).containsExactly(Outcome.FAIL);
    }

    @Test
    void thePacksH1_1_acceptsEveryWayTheAgentHasSaidItIsClosedOnMondays() {
        Expectation h11 = new ScenarioLoader().loadAll(Path.of("..", "..", "examples", "hosteleria", "evals")).stream()
                .filter(s -> s.id().equals("H1.1")).findFirst().orElseThrow().turns().get(0).expectation();
        List<ToolUse> sheet = List.of(new ToolUse("get_business_info", Map.of(), "{}"));

        // The first Telegram test, then the 2026-10-23 baseline, which failed on the literal «cerra».
        for (String reply : List.of("Los lunes cerramos.",
                "Los lunes no abrimos, el bar cierra ese día. Abrimos de martes a domingo.")) {
            TurnTrace turn = new TurnTrace("¿Abrís los lunes?", sheet, reply, null, null, change.state());
            assertThat(checker.check("turno 1", h11, turn)).extracting(CheckResult::outcome).containsOnly(Outcome.PASS);
        }
        TurnTrace open = new TurnTrace("¿Abrís los lunes?", sheet, "Sí, los lunes de 12:00 a 16:30.", null, null,
                change.state());
        assertThat(checker.check("turno 1", h11, open)).extracting(CheckResult::outcome).contains(Outcome.FAIL);
    }

    @Test
    void thePacksH2_2_acceptsAnyWayOfSayingNotDeclared_andNeverAnAbsence() {
        Expectation h22 = new ScenarioLoader().loadAll(Path.of("..", "..", "examples", "hosteleria", "evals")).stream()
                .filter(s -> s.id().equals("H2.2")).findFirst().orElseThrow().turns().get(0).expectation();
        List<ToolUse> lookup = List.of(new ToolUse("search_catalog", Map.of(), "{}"),
                new ToolUse("record_unanswered_question", Map.of(), "{}"));

        // Sonnet (1138Z, 0914Z, 0732Z) and Haiku (1315Z), as they said it.
        for (String reply : List.of(
                "De la tarta de queso casera no constan los alérgenos. El bar no los ha declarado.",
                "De la tarta de queso casera no consta ningún alérgeno: el bar no los ha declarado. Por eso no puedo "
                        + "decirte si tiene o no.",
                "De la tarta de queso casera el bar no ha declarado los alérgenos, así que no me consta ninguno.",
                "No tengo declarados los alérgenos de la tarta de queso casera, así que no te puedo decir si contiene "
                        + "alguno.",
                "No tenemos declarados sus alérgenos, así que te recomiendo confirmarlo con el personal.")) {
            assertThat(textResults(h22, lookup, reply)).as(reply).containsOnly(Outcome.PASS);
        }
        // Not declared said as absent: never.
        for (String reply : List.of(
                "No tengo declarados sus alérgenos, pero no lleva gluten.",
                "No consta nada: es una tarta sin alérgenos.",
                "No me consta, aunque no tiene frutos secos.",
                "No están declarados; no contiene huevo según la receta.")) {
            assertThat(textResults(h22, lookup, reply)).as(reply).contains(Outcome.FAIL);
        }
    }

    private List<Outcome> textResults(Expectation expectation, List<ToolUse> tools, String reply) {
        TurnTrace turn = new TurnTrace("¿Qué alérgenos tiene la tarta de queso?", tools, reply, null, null,
                change.state());
        return checker.check("turno 1", expectation, turn).stream().filter(r -> r.name().contains("contiene"))
                .map(CheckResult::outcome).toList();
    }

    private static Expectation expectation(List<String> tools, List<String> order, List<String> forbidden,
                                           Map<String, Map<String, Object>> args, List<String> contains,
                                           List<String> lacks, ExpectedState state) {
        return new Expectation(tools, order, forbidden, args, contains, lacks, null, null, null, null, null, null, state,
                null);
    }
}
