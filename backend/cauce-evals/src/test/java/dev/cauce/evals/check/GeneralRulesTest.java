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
    private static final Menu PLANS = Menu.load(Path.of("..", "..", "examples", "caucewebs", "caucewebs", "catalogo.json"));
    /** What search_catalog returned for the plans in 1300Z: prices as toPlainString writes them. */
    private static final String PLANS_OUTPUT = "{\"items\":[{\"name\":\"Plan Básico\",\"price\":\"49\",\"ref\":\"plan-basico\"},"
            + "{\"name\":\"Plan Básico con dominio propio\",\"price\":\"59\",\"ref\":\"plan-basico-dominio\"},"
            + "{\"name\":\"Plan Plus\",\"price\":\"79\",\"ref\":\"plan-plus\"}]}";
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
    void aCatalogWithoutAllergens_isNotNamedByTheWordAllergen_butByItsPlansAndPrices() {
        Menu plans = PLANS;

        // A website shows "los 14 alérgenos" of a bar's menu: that is not CauceWebs's catalog.
        assertThat(plans.namesAllergen("La web muestra los 14 alérgenos de cada plato.")).isFalse();
        assertThat(plans.itemsNamedIn("El Plan Plus cuesta 79 € + IVA al año.")).extracting(Menu.Item::ref)
                .containsExactly("plan-plus");
        assertThat(plans.pricesNamedIn("El Plan Plus cuesta 79 € + IVA al año.")).containsExactly(new BigDecimal("79.00"));
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

    @Test
    void theBarsMenuOfTheDay_aPriceOfTheSheet_isGroundedByTheBusinessInformation_notTheMenu() {
        // H1.4, 1223Z: the only amount of the bar's battery that is not a menu price.
        TurnTrace menuOfTheDay = turn("El menú del día cuesta 14,50 €.",
                new ToolUse("get_business_info", Map.of(), "{\"facts\":{\"menu_del_dia\":\"14,50 € con bebida\"}}"));

        assertThat(GeneralRules.g1(menuOfTheDay, List.of(), MENU).outcome()).isEqualTo(Outcome.NOT_APPLICABLE);
        assertThat(GeneralRules.grounded(menuOfTheDay, List.of(), MENU).outcome()).isEqualTo(Outcome.PASS);
        assertThat(GeneralRules.grounded(turn("El menú del día cuesta 14,50 €."), List.of(), MENU).outcome())
                .isEqualTo(Outcome.FAIL);
    }

    @Test
    void aPlanPrice_isGroundedByTheCatalogResult_however_theToolWritesIt() {
        // CW1.1, 1300Z: the tool writes "49", the reply 49 €; the plan with the domain, "59".
        TurnTrace priced = turn("Plan Básico: 49 € + IVA al año. Con dominio propio, 59 € + IVA al año.",
                new ToolUse("search_catalog", Map.of(), PLANS_OUTPUT));

        assertThat(GeneralRules.g1(priced, List.of(), PLANS).outcome()).isEqualTo(Outcome.PASS);
        assertThat(GeneralRules.grounded(priced, List.of(), PLANS).outcome()).isEqualTo(Outcome.PASS);
        // The shop's yearly fee is a catalog amount too: without the search, G1 fails.
        TurnTrace unsearched = turn("La cuota anual de la tienda es de 250 € + IVA.");
        assertThat(GeneralRules.g1(unsearched, List.of(), PLANS).outcome()).isEqualTo(Outcome.FAIL);
        assertThat(GeneralRules.grounded(unsearched, List.of(), PLANS).detail()).contains("250.00");
    }

    @Test
    void aPlanWhoseFeaturesListAnotherPlan_isNotThatOtherPlan() {
        // CW4.2, 1300Z: the Plus "incluye todo lo del Plan Básico, dominio propio .es" is not the
        // Plan Básico con dominio propio; the words never match across the comma or the full stop.
        assertThat(PLANS.itemsNamedIn("Incluye todo lo del Plan Básico, dominio propio .es y menú del día."))
                .extracting(Menu.Item::ref).containsExactly("plan-basico");
        assertThat(PLANS.itemsNamedIn("Incluye: - todo lo del Plan Básico. - dominio propio .es."))
                .extracting(Menu.Item::ref).containsExactly("plan-basico");
        assertThat(PLANS.itemsNamedIn("El Plan Básico con dominio propio cuesta 59 €.")).extracting(Menu.Item::ref)
                .contains("plan-basico-dominio");
    }

    @Test
    void aPlanTheTurnsOtherToolsName_isReadFromThem_notFromTheCatalog() {
        // CW1.4: «te cuento el Plan Tienda» after the business information that names it.
        TurnTrace offered = turn("No, con Bizum no. ¿Quieres que te cuente el Plan Tienda?",
                new ToolUse("get_business_info", Map.of(), "{\"facts\":{\"tienda_cobros\":\"En el Plan Tienda, los pagos...\"}}"));
        // CW3.5: the Plan Plus of the case list_my_cases returned.
        TurnTrace aCase = turn("Tengo tu petición: una demo del Plan Plus para el bar El Puente.",
                new ToolUse("list_my_cases", Map.of(), "{\"cases\":[{\"summary\":\"Bar El Puente, Úbeda. Quiere ver una demo del Plan Plus.\"}]}"));
        // A plan nobody said in the turn is still a first mention that needs the search.
        TurnTrace unsaid = turn("¿Quieres que te cuente el Plan Plus?",
                new ToolUse("get_business_info", Map.of(), "{\"facts\":{}}"));

        assertThat(GeneralRules.g1(offered, List.of(), PLANS).outcome()).isEqualTo(Outcome.NOT_APPLICABLE);
        assertThat(GeneralRules.grounded(offered, List.of(), PLANS).outcome()).isEqualTo(Outcome.PASS);
        assertThat(GeneralRules.g1(aCase, List.of(), PLANS).outcome()).isEqualTo(Outcome.NOT_APPLICABLE);
        assertThat(GeneralRules.g1(unsaid, List.of(), PLANS).outcome()).isEqualTo(Outcome.FAIL);
    }

    @Test
    void theReferralAmounts_comeFromTheBusinessInformation_andACustomersFigureFromNoTool() {
        // CW4.4, turn 3: the 49 € reward is a fact of the sheet, equal to a plan's price by chance.
        TurnTrace reward = turn("El primer bar que traigas te da un año de web Básica gratis: 49 € menos.",
                new ToolUse("get_business_info", Map.of(), "{\"facts\":{\"recomienda_quien_trae\":\"...gratis (49 € menos en tu próxima renovación)...\"}}"));
        // CW4.1: the customer's 50 €, repeated by the agent to reject it, is in no result of the turn.
        TurnTrace repeated = turn("No puedo dejártelo en 50 €: el Plan Plus cuesta 79 € + IVA al año.",
                new ToolUse("search_catalog", Map.of(), PLANS_OUTPUT));

        assertThat(GeneralRules.g1(reward, List.of(), PLANS).outcome()).isEqualTo(Outcome.NOT_APPLICABLE);
        assertThat(GeneralRules.grounded(reward, List.of(), PLANS).outcome()).isEqualTo(Outcome.PASS);
        CheckResult rejected = GeneralRules.grounded(repeated, List.of(), PLANS);
        assertThat(rejected.outcome()).isEqualTo(Outcome.FAIL);
        assertThat(rejected.detail()).contains("50.00").doesNotContain("79.00");
    }

    @Test
    void numbersIn_readsAToolResultsAmounts_notThePiecesOfAnIdOrADate() {
        assertThat(Menu.numbersIn("{\"price\":\"49\",\"other\":\"5.50\",\"fact\":\"14,50 € y 24,99 €\"}"))
                .containsExactlyInAnyOrder(new BigDecimal("49.00"), new BigDecimal("5.50"), new BigDecimal("14.50"),
                        new BigDecimal("24.99"));
        assertThat(Menu.numbersIn("{\"id\":\"0192f3a4-12ab-7c00-8d9e-000000000012\",\"code\":\"RUGF49\"}")).isEmpty();
    }

    private static TurnTrace turn(String reply, ToolUse... tools) {
        return new TurnTrace("?", List.of(tools), reply, reply, null,
                new ObservedState(List.of(), List.of(), 0, List.of(), 0, null));
    }
}
