package dev.cauce.evals.check;

import dev.cauce.evals.check.TurnTrace.ToolUse;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The two rules every turn of every scenario is checked against, without a scenario writing them
 * (ADR 0009, E5). Both are blocking, and both are "not applicable" to a turn whose reply does not
 * talk about the menu, so such turns do not inflate their rate.
 *
 * <p>Naming again a dish the agent already named in an earlier reply of the conversation is not
 * talking about the menu: that earlier reply was checked in its own turn. Stating a fact of the
 * menu (a price or an allergen) always is, and so is naming a dish for the first time (G3, `1349Z`:
 * «Te he anotado la duda de la tarta de queso», the dish of the turn before, with no fact about it).
 */
public final class GeneralRules {

    public static final String G1 = "G1 · carta consultada antes de hablar de ella";
    public static final String GROUNDED = "carta sin inventar";
    static final String SEARCH_CATALOG = "search_catalog";

    private GeneralRules() {
    }

    /**
     * G1: a reply that names a menu price, an allergen or a dish not named in {@code earlierReplies}
     * (the agent's replies of the earlier turns, in order) comes after a menu search in its turn.
     */
    public static CheckResult g1(TurnTrace turn, List<String> earlierReplies, Menu menu) {
        if (!talksAboutTheMenu(turn.reply(), earlierReplies, menu)) {
            return new CheckResult(G1, CheckResult.Outcome.NOT_APPLICABLE, "la respuesta no habla de la carta");
        }
        boolean searched = turn.tools().stream().anyMatch(tool -> tool.name().equals(SEARCH_CATALOG));
        return CheckResult.of(G1, searched, searched ? "search_catalog en el turno"
                : "habla de la carta sin search_catalog en el turno");
    }

    /**
     * The menu is not invented: every menu price the reply names, and every dish it names that is not
     * in {@code earlierReplies}, appears in a tool result of the same turn.
     */
    public static CheckResult grounded(TurnTrace turn, List<String> earlierReplies, Menu menu) {
        List<Menu.Item> items = newItems(turn.reply(), earlierReplies, menu);
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

    /** Whether {@code reply} states a menu fact or names a dish the earlier replies did not name. */
    private static boolean talksAboutTheMenu(String reply, List<String> earlierReplies, Menu menu) {
        return !newItems(reply, earlierReplies, menu).isEmpty() || !menu.pricesNamedIn(reply).isEmpty()
                || menu.namesAllergen(reply);
    }

    /** The dishes {@code reply} names that none of {@code earlierReplies} named. */
    private static List<Menu.Item> newItems(String reply, List<String> earlierReplies, Menu menu) {
        Set<String> named = earlierReplies.stream().flatMap(earlier -> menu.itemsNamedIn(earlier).stream())
                .map(Menu.Item::ref).collect(Collectors.toSet());
        return menu.itemsNamedIn(reply).stream().filter(item -> !named.contains(item.ref())).toList();
    }
}
