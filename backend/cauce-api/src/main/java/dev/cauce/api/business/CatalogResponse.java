package dev.cauce.api.business;

import dev.cauce.core.business.catalog.Catalog;
import dev.cauce.core.business.catalog.CatalogItem;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** A catalog over the wire, items exactly as stored (an absent attribute stays absent). */
public record CatalogResponse(
        UUID id,
        UUID tenantId,
        List<String> declaredAttributes,
        List<Item> items,
        Instant createdAt,
        Instant updatedAt) {

    public record Item(String ref, String section, String name, String description, BigDecimal price,
                       Map<String, Object> attributes) {

        static Item from(CatalogItem item) {
            return new Item(item.ref(), item.section(), item.name(), item.description(), item.price(),
                    item.attributes());
        }
    }

    public static CatalogResponse from(Catalog catalog) {
        return new CatalogResponse(catalog.id(), catalog.tenantId(), catalog.declaredAttributes(),
                catalog.items().stream().map(Item::from).toList(), catalog.createdAt(), catalog.updatedAt());
    }
}
