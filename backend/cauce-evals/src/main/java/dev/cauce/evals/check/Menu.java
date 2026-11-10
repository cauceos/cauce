package dev.cauce.evals.check;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The fixture's catalog ({@code carta.json} for a menu, {@code catalogo.json} otherwise), as the general
 * rules read a reply (ADR 0009, E5). A
 * reply names an item when it holds the item's keyword — the words of its {@code ref}, in order and
 * with up to two words between them, never across a punctuation mark ({@code bravas}, {@code tarta de
 * queso} for {@code tarta-queso}, {@code cana}; but «todo lo del Plan Básico, dominio propio» is not
 * {@code plan-basico-dominio}) — as whole words, ignoring case and accents, so a birthday «tarta» alone
 * is not the menu's cheesecake; it names a catalog amount when an amount it writes ({@code 5,50},
 * {@code 8 €}) equals an item's price or a number among its attributes (a plan's yearly fee); it names
 * an allergen when it holds the stem of one the menu declares, or the word itself.
 */
public final class Menu {

    private static final Pattern AMOUNT = Pattern.compile("(\\d{1,4})(?:[.,](\\d{1,2}))?\\s*(?:€|euros?)|(\\d{1,4})[.,](\\d{2})");
    /** A number a tool result writes ({@code "49"}, {@code "5.50"}, {@code 14,50 €}), not a piece of an id or a code. */
    private static final Pattern NUMBER = Pattern.compile("(?<![\\w.,-])(\\d{1,6})(?:[.,](\\d{1,2}))?(?![\\w-]|[.,]\\d)");
    /** What may stand between the words of a keyword: spaces and quotes, never a punctuation mark. */
    private static final String GAP = "[^\\w.,;:!?()]+";

    private final List<Item> items;
    private final Set<BigDecimal> prices;
    private final List<Pattern> allergenStems;

    /**
     * One item: its keyword, its price (absent for an item without one), the other amounts its
     * attributes state, and the strings a tool result names it by.
     */
    public record Item(String ref, String name, String keyword, BigDecimal price, Set<BigDecimal> amounts) {

        public Item {
            amounts = Set.copyOf(amounts);
        }
    }

    public Menu(List<Item> items, List<String> allergens) {
        this.items = List.copyOf(items);
        this.prices = new LinkedHashSet<>();
        items.forEach(item -> {
            if (item.price() != null) {
                prices.add(item.price());
            }
            prices.addAll(item.amounts());
        });
        List<Pattern> stems = new ArrayList<>();
        if (!allergens.isEmpty()) {
            // The word itself counts only for a catalog of dishes, which declares allergens: a shop that
            // sells websites says "los 14 alérgenos" about what its websites show, not about its catalog.
            stems.add(Pattern.compile("\\balergen"));
        }
        for (String allergen : allergens) {
            String folded = Text.folded(allergen);
            // A stem, so "huevos" names "huevo" too and "lacteos" names "lacteo".
            String stem = folded.length() > 5 ? folded.substring(0, folded.length() - 2) : folded;
            stems.add(Pattern.compile("\\b" + Pattern.quote(stem)));
        }
        this.allergenStems = List.copyOf(stems);
    }

    /** The catalog of a fixture's catalog file; its {@code alergenos}, where it declares them. */
    public static Menu load(Path cartaJson) {
        JsonNode carta;
        try {
            carta = new ObjectMapper().readTree(cartaJson.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + cartaJson, e);
        }
        List<Item> items = new ArrayList<>();
        Set<String> allergens = new LinkedHashSet<>();
        for (JsonNode item : carta.path("items")) {
            String ref = item.path("ref").asText();
            BigDecimal price = item.path("price").isNumber()
                    ? item.path("price").decimalValue().setScale(2, RoundingMode.HALF_UP) : null;
            Set<BigDecimal> amounts = new LinkedHashSet<>();
            item.path("attributes").forEach(value -> {
                if (value.isNumber()) {
                    amounts.add(value.decimalValue().setScale(2, RoundingMode.HALF_UP));
                }
            });
            items.add(new Item(ref, item.path("name").asText(), Text.folded(String.join(" ", ref.split("-"))),
                    price, amounts));
            JsonNode declared = item.path("attributes").path("alergenos");
            if (declared.isArray()) {
                declared.forEach(a -> allergens.add(a.asText()));
            }
        }
        return new Menu(items, List.copyOf(allergens));
    }

    /** The items {@code text} names. */
    public List<Item> itemsNamedIn(String text) {
        String folded = Text.folded(text);
        return items.stream()
                .filter(item -> keywordPattern(item.keyword()).matcher(folded).find())
                .toList();
    }

    /** The keyword's words in order, whole, with up to two words between them («tarta de queso»). */
    private static Pattern keywordPattern(String keyword) {
        return Pattern.compile("\\b" + Arrays.stream(keyword.split(" ")).map(Pattern::quote)
                .collect(Collectors.joining("(?:" + GAP + "\\w+){0,2}?" + GAP)) + "\\b");
    }

    /** The catalog amounts {@code text} writes: item prices and the amounts of their attributes. */
    public Set<BigDecimal> pricesNamedIn(String text) {
        Set<BigDecimal> named = new LinkedHashSet<>(amountsIn(text));
        named.retainAll(prices);
        return named;
    }

    /** Every amount {@code text} writes as money ({@code 5,50}, {@code 8 €}, {@code 24,99 euros}), at scale 2. */
    public static Set<BigDecimal> amountsIn(String text) {
        Set<BigDecimal> named = new LinkedHashSet<>();
        Matcher m = AMOUNT.matcher(text == null ? "" : text);
        while (m.find()) {
            String units = m.group(1) != null ? m.group(1) : m.group(3);
            String cents = m.group(1) != null ? m.group(2) : m.group(4);
            named.add(scaled(units, cents));
        }
        return named;
    }

    /**
     * Every number a tool result writes, at scale 2, however it is written: {@code "49"} and
     * {@code "49.00"} are the same amount, and so are {@code 14,50 €} and {@code 14.50}.
     */
    public static Set<BigDecimal> numbersIn(String output) {
        Set<BigDecimal> numbers = new LinkedHashSet<>();
        Matcher m = NUMBER.matcher(output == null ? "" : output);
        while (m.find()) {
            numbers.add(scaled(m.group(1), m.group(2)));
        }
        return numbers;
    }

    private static BigDecimal scaled(String units, String cents) {
        return new BigDecimal(units + "." + (cents == null ? "00" : cents)).setScale(2, RoundingMode.HALF_UP);
    }

    /** Whether {@code text} names an allergen. */
    public boolean namesAllergen(String text) {
        String folded = Text.folded(text);
        return allergenStems.stream().anyMatch(stem -> stem.matcher(folded).find());
    }

    /** Whether {@code text} talks about the menu at all: an item, a catalog amount or an allergen. */
    public boolean talksAbout(String text) {
        return !itemsNamedIn(text).isEmpty() || !pricesNamedIn(text).isEmpty() || namesAllergen(text);
    }
}
