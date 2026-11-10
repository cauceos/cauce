package dev.cauce.channels;

import dev.cauce.core.business.catalog.Catalog;
import dev.cauce.core.business.catalog.CatalogItem;
import dev.cauce.core.business.catalog.CatalogSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * What a reply's product cards show, read from the catalog when they are shown (ADR 0014, WC8): a card
 * stores its ref alone, so its name, description and price are the catalog's at that moment, and a ref
 * no longer in the catalog is left out. Runs under the tenant context of whoever reads the reply.
 */
@Component
public class ProductCards {

    /** A card as the catalog fills it in now. */
    public record Card(String ref, String name, String description, BigDecimal price) {
    }

    private final ObjectProvider<CatalogSource> catalogs;

    public ProductCards(ObjectProvider<CatalogSource> catalogs) {
        this.catalogs = catalogs;
    }

    /** The cards of {@code refs} in {@code tenantId}'s catalog, in order; refs it no longer holds left out. */
    public List<Card> resolve(UUID tenantId, List<String> refs) {
        if (refs.isEmpty()) {
            return List.of();
        }
        CatalogSource source = catalogs.getIfAvailable();
        Optional<Catalog> catalog = source == null ? Optional.empty() : source.findCatalog(tenantId);
        if (catalog.isEmpty()) {
            return List.of();
        }
        Map<String, CatalogItem> byRef = catalog.get().items().stream()
                .collect(Collectors.toMap(CatalogItem::ref, Function.identity(), (a, b) -> a));
        return refs.stream().map(byRef::get).filter(item -> item != null)
                .map(item -> new Card(item.ref(), item.name(), item.description(), item.price())).toList();
    }

    /**
     * The cards as text, for a channel that cannot carry one (ADR 0014, WC8): a line per card with its
     * name and, when it has one, its price, written with a decimal comma ({@code 24,99 €}).
     */
    public static String asText(List<Card> cards) {
        return cards.stream().map(card -> "• " + card.name() + (card.price() == null ? "" : ": " + price(card.price())))
                .collect(Collectors.joining("\n"));
    }

    static String price(BigDecimal price) {
        BigDecimal plain = price.stripTrailingZeros();
        String text = plain.scale() <= 0 ? plain.toPlainString() : price.setScale(2, RoundingMode.HALF_UP).toPlainString();
        return text.replace('.', ',') + " €";
    }
}
