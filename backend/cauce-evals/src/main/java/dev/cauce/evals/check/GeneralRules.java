package dev.cauce.evals.check;

import dev.cauce.evals.check.TurnTrace.ToolUse;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The two rules every turn of every scenario is checked against, without a scenario writing them
 * (ADR 0009, E5). Both are blocking, and both are "not applicable" to a turn whose reply does not
 * talk about the menu, so such turns do not inflate their rate.
 */
public final class GeneralRules {

    public static final String G1 = "G1 · carta consultada antes de hablar de ella";
    public static final String GROUNDED = "carta sin inventar";
    static final String SEARCH_CATALOG = "search_catalog";

    private GeneralRules() {
    }

    /** G1: a reply that names a dish, a menu price or an allergen comes after a menu search in its turn. */
    public static CheckResult g1(TurnTrace turn, Menu menu) {
        if (!menu.talksAbout(turn.reply())) {
            return new CheckResult(G1, CheckResult.Outcome.NOT_APPLICABLE, "la respuesta no habla de la carta");
        }
        boolean searched = turn.tools().stream().anyMatch(tool -> tool.name().equals(SEARCH_CATALOG));
        return CheckResult.of(G1, searched, searched ? "search_catalog en el turno"
                : "habla de la carta sin search_catalog en el turno");
    }

    /**
     * The menu is not invented: every dish and every menu price the reply names appears in a tool
     * result of the same turn.
     */
    public static CheckResult grounded(TurnTrace turn, Menu menu) {
        List<Menu.Item> items = menu.itemsNamedIn(turn.reply());
        var prices = menu.pricesNamedIn(turn.reply());
        if (items.isEmpty() && prices.isEmpty()) {
            return new CheckResult(GROUNDED, CheckResult.Outcome.NOT_APPLICABLE, "la respuesta no nombra platos ni precios");
        }
        String outputs = Text.folded(turn.tools().stream().map(ToolUse::output).collect(Collectors.joining(" ")));
        List<String> missing = new ArrayList<>();
        for (Menu.Item item : items) {
            if (!outputs.contains(Text.folded(item.ref())) && !outputs.contains(Text.folded(item.name()))) {
                missing.add(item.name());
            }
        }
        for (BigDecimal price : prices) {
            if (!outputs.contains(price.toPlainString())) {
                missing.add(price.toPlainString() + " €");
            }
        }
        return CheckResult.of(GROUNDED, missing.isEmpty(), missing.isEmpty()
                ? "todo lo nombrado sale de un resultado del turno" : "nombrado sin salir del turno: " + missing);
    }
}
