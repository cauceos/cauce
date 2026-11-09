package dev.cauce.evals.check;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cauce.evals.check.CheckResult.Outcome;
import dev.cauce.evals.check.TurnTrace.ObservedState;
import dev.cauce.evals.check.TurnTrace.ToolUse;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GeneralRulesTest {

    private static final Menu MENU = Menu.load(Path.of("..", "..", "examples", "hosteleria", "bar-ejemplo", "carta.json"));
    /** The celiac answer of the 2026-10-22 test, and the menu search it came from. */
    private static final String CELIAC = "Estos platos constan sin gluten en la carta: patatas bravas (5,50 €), "
            + "pimientos de Padrón (6,00 €) y ternasco de Aragón asado con patatas (18,50 €).";
    private static final String CATALOG_OUTPUT = "{\"items\":[{\"name\":\"Patatas bravas\",\"price\":\"5.50\",\"ref\":"
            + "\"bravas\"},{\"name\":\"Pimientos de Padrón\",\"price\":\"6.00\",\"ref\":\"pimientos\"},{\"name\":"
            + "\"Ternasco de Aragón asado con patatas\",\"price\":\"18.50\",\"ref\":\"ternasco\"}]}";

    @Test
    void theMenu_isNamedByItemKeywordPriceOrAllergen_notByTheSheetsPrices() {
        assertThat(MENU.itemsNamedIn(CELIAC)).extracting(Menu.Item::ref).containsExactly("bravas", "pimientos", "ternasco");
        assertThat(MENU.pricesNamedIn(CELIAC)).contains(new BigDecimal("5.50"), new BigDecimal("18.50"));
        assertThat(MENU.namesAllergen("¿Llevan huevo?")).isTrue();
        assertThat(MENU.itemsNamedIn("Una caña, por favor")).extracting(Menu.Item::ref).containsExactly("cana");
        // The 14,50 € of the menú del día comes from the sheet, not the menu; closing days are not the menu.
        assertThat(MENU.talksAbout("Tenemos el menú del día a 14,50 €.")).isFalse();
        assertThat(MENU.talksAbout("Los lunes cerramos.")).isFalse();
    }

    @Test
    void anItemOfSeveralWords_isNamedByAllOfThem_soABirthdayCakeIsNotTheCheesecake() {
        // H4.6 and H4.7 of the 2026-10-22 baseline: «tarta, decoración, menú…» for a birthday.
        assertThat(MENU.talksAbout("Cualquier detalle que quieras añadir (tarta, decoración, menú…)")).isFalse();
        assertThat(MENU.itemsNamedIn("La tarta de queso casera, 5,00 €")).extracting(Menu.Item::ref)
                .containsExactly("tarta-queso");
        assertThat(MENU.itemsNamedIn("Una copa de vino tinto")).extracting(Menu.Item::ref).containsExactly("vino-tinto");
    }

    @Test
    void g1_passesAfterAMenuSearch_failsWithout_andDoesNotApplyToOtherTurns() {
        TurnTrace searched = turn(CELIAC, new ToolUse("search_catalog", Map.of(), CATALOG_OUTPUT));
        TurnTrace unsearched = turn(CELIAC, new ToolUse("get_business_info", Map.of(), "{}"));

        assertThat(GeneralRules.g1(searched, List.of(), MENU).outcome()).isEqualTo(Outcome.PASS);
        assertThat(GeneralRules.g1(unsearched, List.of(), MENU).outcome()).isEqualTo(Outcome.FAIL);
        assertThat(GeneralRules.g1(turn("Los lunes cerramos."), List.of(), MENU).outcome()).isEqualTo(Outcome.NOT_APPLICABLE);
    }

    @Test
    void grounded_failsForADishOrPriceNotInTheTurnsResults() {
        TurnTrace grounded = turn(CELIAC, new ToolUse("search_catalog", Map.of(), CATALOG_OUTPUT));
        TurnTrace invented = turn("Te recomiendo los calamares a la romana (12,00 €).",
                new ToolUse("search_catalog", Map.of(), CATALOG_OUTPUT));

        assertThat(GeneralRules.grounded(grounded, List.of(), MENU).outcome()).isEqualTo(Outcome.PASS);
        CheckResult result = GeneralRules.grounded(invented, List.of(), MENU);
        assertThat(result.outcome()).isEqualTo(Outcome.FAIL);
        assertThat(result.detail()).contains("Calamares a la romana").contains("12.00");
    }

    @Test
    void aDishTheAgentNamedBefore_namedAgainWithoutAFact_isNotTalkingAboutTheMenu_butAFactStillNeedsTheSearch() {
        // G3, 1349Z, repetition 3: turn 4 searched and named the cheesecake; turn 5 noted the question.
        List<String> earlier = List.of("De la tarta de queso casera el bar no ha declarado los alérgenos. "
                + "¿Quieres que anote lo de la tarta?");
        TurnTrace noted = turn("Te he anotado la duda de la tarta de queso. Te dejo la reserva pendiente de confirmar.",
                new ToolUse("record_unanswered_question", Map.of(), "{}"));
        TurnTrace priced = turn("La tarta de queso cuesta 5,00 €.");
        TurnTrace allergen = turn("La tarta de queso no lleva gluten.");
        TurnTrace firstTime = turn("Te recomiendo los pimientos de Padrón.");

        assertThat(GeneralRules.g1(noted, earlier, MENU).outcome()).isEqualTo(Outcome.NOT_APPLICABLE);
        assertThat(GeneralRules.grounded(noted, earlier, MENU).outcome()).isEqualTo(Outcome.NOT_APPLICABLE);
        assertThat(GeneralRules.g1(priced, earlier, MENU).outcome()).isEqualTo(Outcome.FAIL);
        assertThat(GeneralRules.grounded(priced, earlier, MENU).detail()).contains("5.00");
        assertThat(GeneralRules.g1(allergen, earlier, MENU).outcome()).isEqualTo(Outcome.FAIL);
        assertThat(GeneralRules.g1(firstTime, earlier, MENU).outcome()).isEqualTo(Outcome.FAIL);
        assertThat(GeneralRules.grounded(firstTime, earlier, MENU).detail()).contains("Pimientos de Padrón");
        // Without the earlier reply, the same note names the dish for the first time.
        assertThat(GeneralRules.g1(noted, List.of(), MENU).outcome()).isEqualTo(Outcome.FAIL);
    }

    private static TurnTrace turn(String reply, ToolUse... tools) {
        return new TurnTrace("?", List.of(tools), reply, reply, null,
                new ObservedState(List.of(), List.of(), 0, List.of(), 0, null));
    }
}
