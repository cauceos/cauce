package dev.cauce.evals.check;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The fixture's menu ({@code carta.json}), as the general rules read a reply (ADR 0009, E5). A
 * reply names an item when it holds the item's keyword — the first word of its {@code ref}
 * ({@code bravas}, {@code tarta}, {@code cana}) — as a whole word, ignoring case and accents; it
 * names a price when an amount it writes ({@code 5,50}, {@code 8 €}) equals an item's price; it
 * names an allergen when it holds the stem of one the menu declares, or the word itself.
 */
public final class Menu {

    private static final Pattern AMOUNT = Pattern.compile("(\\d{1,4})(?:[.,](\\d{1,2}))?\\s*(?:€|euros?)|(\\d{1,4})[.,](\\d{2})");

    private final List<Item> items;
    private final Set<BigDecimal> prices;
    private final List<Pattern> allergenStems;

    /** One item: its keyword, its price, and the strings a tool result names it by. */
    public record Item(String ref, String name, String keyword, BigDecimal price) {
    }

    public Menu(List<Item> items, List<String> allergens) {
        this.items = List.copyOf(items);
        this.prices = new LinkedHashSet<>();
        items.forEach(item -> prices.add(item.price()));
        List<Pattern> stems = new ArrayList<>();
        stems.add(Pattern.compile("\\balergen"));
        for (String allergen : allergens) {
            String folded = Text.folded(allergen);
            // A stem, so "huevos" names "huevo" too and "lacteos" names "lacteo".
            String stem = folded.length() > 5 ? folded.substring(0, folded.length() - 2) : folded;
            stems.add(Pattern.compile("\\b" + Pattern.quote(stem)));
        }
        this.allergenStems = List.copyOf(stems);
    }

    /** The menu of a fixture's {@code carta.json}. */
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
            items.add(new Item(ref, item.path("name").asText(), Text.folded(ref.split("-")[0]),
                    item.path("price").decimalValue().setScale(2, RoundingMode.HALF_UP)));
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
                .filter(item -> Pattern.compile("\\b" + Pattern.quote(item.keyword()) + "\\b").matcher(folded).find())
                .toList();
    }

    /** The menu prices {@code text} writes. */
    public Set<BigDecimal> pricesNamedIn(String text) {
        Set<BigDecimal> named = new LinkedHashSet<>();
        Matcher m = AMOUNT.matcher(text == null ? "" : text);
        while (m.find()) {
            String units = m.group(1) != null ? m.group(1) : m.group(3);
            String cents = m.group(1) != null ? m.group(2) : m.group(4);
            BigDecimal amount = new BigDecimal(units + "." + (cents == null ? "00" : cents))
                    .setScale(2, RoundingMode.HALF_UP);
            if (prices.contains(amount)) {
                named.add(amount);
            }
        }
        return named;
    }

    /** Whether {@code text} names an allergen. */
    public boolean namesAllergen(String text) {
        String folded = Text.folded(text);
        return allergenStems.stream().anyMatch(stem -> stem.matcher(folded).find());
    }

    /** Whether {@code text} talks about the menu at all: an item, a menu price or an allergen. */
    public boolean talksAbout(String text) {
        return !itemsNamedIn(text).isEmpty() || !pricesNamedIn(text).isEmpty() || namesAllergen(text);
    }
}
