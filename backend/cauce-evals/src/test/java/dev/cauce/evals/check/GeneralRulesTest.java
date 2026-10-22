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
    void g1_passesAfterAMenuSearch_failsWithout_andDoesNotApplyToOtherTurns() {
        TurnTrace searched = turn(CELIAC, new ToolUse("search_catalog", Map.of(), CATALOG_OUTPUT));
        TurnTrace unsearched = turn(CELIAC, new ToolUse("get_business_info", Map.of(), "{}"));

        assertThat(GeneralRules.g1(searched, MENU).outcome()).isEqualTo(Outcome.PASS);
        assertThat(GeneralRules.g1(unsearched, MENU).outcome()).isEqualTo(Outcome.FAIL);
        assertThat(GeneralRules.g1(turn("Los lunes cerramos."), MENU).outcome()).isEqualTo(Outcome.NOT_APPLICABLE);
    }

    @Test
    void grounded_failsForADishOrPriceNotInTheTurnsResults() {
        TurnTrace grounded = turn(CELIAC, new ToolUse("search_catalog", Map.of(), CATALOG_OUTPUT));
        TurnTrace invented = turn("Te recomiendo los calamares a la romana (12,00 €).",
                new ToolUse("search_catalog", Map.of(), CATALOG_OUTPUT));

        assertThat(GeneralRules.grounded(grounded, MENU).outcome()).isEqualTo(Outcome.PASS);
        CheckResult result = GeneralRules.grounded(invented, MENU);
        assertThat(result.outcome()).isEqualTo(Outcome.FAIL);
        assertThat(result.detail()).contains("Calamares a la romana").contains("12.00");
    }

    private static TurnTrace turn(String reply, ToolUse... tools) {
        return new TurnTrace("?", List.of(tools), reply, reply, null,
                new ObservedState(List.of(), List.of(), 0, List.of(), 0, null));
    }
}
