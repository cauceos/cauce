package dev.cauce.evals.check;

import dev.cauce.evals.check.TurnTrace.ToolUse;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * The two rules every turn of every scenario is checked against, without a scenario writing them
 * (ADR 0009, E5). Both are blocking, and both are "not applicable" to a turn whose reply does not
 * talk about the menu, so such turns do not inflate their rate.
 *
 * <p>The rules hold for any catalog, a bar's menu or a shop's plans. Naming again an item the agent
 * already named in an earlier reply of the conversation is not talking about the catalog: that
 * earlier reply was checked in its own turn. Stating a fact of the catalog (an amount or an
 * allergen) always is, and so is naming an item for the first time (G3, `1349Z`: «Te he anotado la
 * duda de la tarta de queso», the dish of the turn before, with no fact about it) — unless another
 * tool of the same turn says it: «te cuento el Plan Tienda» after the business information that
 * names it, or the Plan Plus of the case {@code list_my_cases} just returned, is read from that
 * result, not from the catalog (CauceWebs, {@code 1300Z}).
 *
 * <p>Amounts are compared as numbers: the catalog tool writes {@code "49"} for a price of 49 and
 * {@code "5.50"} for one of 5,50, and a reply writes {@code 49 €} or {@code 5,50 €}.
 */
public final class GeneralRules {

    public static final String G1 = "G1 · carta consultada antes de hablar de ella";
    public static final String GROUNDED = "carta sin inventar";
    static final String SEARCH_CATALOG = "search_catalog";

    private GeneralRules() {
    }

    /**
     * G1: a reply that names a catalog amount, an allergen or an item not named in {@code earlierReplies}
     * (the agent's replies of the earlier turns, in order), and that no other tool of the turn states,
     * comes after a catalog search in its turn.
     */
    public static CheckResult g1(TurnTrace turn, List<String> earlierReplies, Menu menu) {
        if (!talksAboutTheMenu(turn, earlierReplies, menu)) {
            return new CheckResult(G1, CheckResult.Outcome.NOT_APPLICABLE, "la respuesta no habla de la carta");
        }
        boolean searched = turn.tools().stream().anyMatch(tool -> tool.name().equals(SEARCH_CATALOG));
        return CheckResult.of(G1, searched, searched ? "search_catalog en el turno"
                : "habla de la carta sin search_catalog en el turno");
    }

    /**
     * Nothing is invented: every amount the reply writes, of the catalog or not, and every item it names
     * that is not in {@code earlierReplies}, appears in a tool result of the same turn. An amount the
     * customer wrote and the agent repeats, to accept it or to reject it, appears in none.
     */
    public static CheckResult grounded(TurnTrace turn, List<String> earlierReplies, Menu menu) {
        List<Menu.Item> items = newItems(turn.reply(), earlierReplies, menu);
        Set<BigDecimal> amounts = Menu.amountsIn(turn.reply());
        if (items.isEmpty() && amounts.isEmpty()) {
            return new CheckResult(GROUNDED, CheckResult.Outcome.NOT_APPLICABLE, "la respuesta no nombra platos ni precios");
        }
        String raw = outputsOf(turn, tool -> true);
        String outputs = Text.folded(raw);
        Set<BigDecimal> numbers = Menu.numbersIn(raw);
        List<String> missing = new ArrayList<>();
        for (Menu.Item item : items) {
            if (!attested(item, outputs)) {
                missing.add(item.name());
            }
        }
        for (BigDecimal amount : amounts) {
            if (!numbers.contains(amount)) {
                missing.add(amount.toPlainString() + " €");
            }
        }
        return CheckResult.of(GROUNDED, missing.isEmpty(), missing.isEmpty()
                ? "todo lo nombrado sale de un resultado del turno" : "nombrado sin salir del turno: " + missing);
    }

    /**
     * Whether the reply states a catalog fact or names an item the earlier replies did not name, other
     * than what another tool of the turn (the business information, a case) already says.
     */
    private static boolean talksAboutTheMenu(TurnTrace turn, List<String> earlierReplies, Menu menu) {
        String raw = outputsOf(turn, tool -> !tool.name().equals(SEARCH_CATALOG));
        String others = Text.folded(raw);
        Set<BigDecimal> otherNumbers = Menu.numbersIn(raw);
        return newItems(turn.reply(), earlierReplies, menu).stream().anyMatch(item -> !attested(item, others))
                || menu.pricesNamedIn(turn.reply()).stream().anyMatch(amount -> !otherNumbers.contains(amount))
                || menu.namesAllergen(turn.reply());
    }

    /** The outputs of the turn's tools that {@code which} accepts, joined. */
    private static String outputsOf(TurnTrace turn, Predicate<ToolUse> which) {
        return turn.tools().stream().filter(which).map(ToolUse::output).filter(Objects::nonNull)
                .collect(Collectors.joining(" "));
    }

    /** Whether the folded {@code outputs} name {@code item}, by its ref or by its name. */
    private static boolean attested(Menu.Item item, String outputs) {
        return outputs.contains(Text.folded(item.ref())) || outputs.contains(Text.folded(item.name()));
    }

    /** The dishes {@code reply} names that none of {@code earlierReplies} named. */
    private static List<Menu.Item> newItems(String reply, List<String> earlierReplies, Menu menu) {
        Set<String> named = earlierReplies.stream().flatMap(earlier -> menu.itemsNamedIn(earlier).stream())
                .map(Menu.Item::ref).collect(Collectors.toSet());
        return menu.itemsNamedIn(reply).stream().filter(item -> !named.contains(item.ref())).toList();
    }
}
