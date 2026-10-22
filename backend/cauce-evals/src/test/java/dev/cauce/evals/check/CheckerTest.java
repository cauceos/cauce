package dev.cauce.evals.check;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cauce.evals.check.CheckResult.Outcome;
import dev.cauce.evals.check.TurnTrace.ObservedBooking;
import dev.cauce.evals.check.TurnTrace.ObservedNotice;
import dev.cauce.evals.check.TurnTrace.ObservedState;
import dev.cauce.evals.check.TurnTrace.ToolUse;
import dev.cauce.evals.scenario.RelativeDates;
import dev.cauce.evals.scenario.Scenario.Expectation;
import dev.cauce.evals.scenario.Scenario.ExpectedState;
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

    private static Expectation expectation(List<String> tools, List<String> order, List<String> forbidden,
                                           Map<String, Map<String, Object>> args, List<String> contains,
                                           List<String> lacks, ExpectedState state) {
        return new Expectation(tools, order, forbidden, args, contains, lacks, null, null, null, null, null, null, state,
                null);
    }
}
