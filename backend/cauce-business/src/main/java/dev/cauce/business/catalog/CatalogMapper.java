package dev.cauce.business.catalog;

import dev.cauce.core.business.catalog.Catalog;
import dev.cauce.core.business.catalog.CatalogItem;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Hand-written mapper between {@link Catalog} and {@link CatalogEntity}. An item is stored as
 * {ref, section, name, description?, price?, attributes}; the price as its decimal text, so no
 * floating-point rounding ever touches it.
 */
@Component
public class CatalogMapper {

    public CatalogEntity toEntity(Catalog catalog) {
        List<Map<String, Object>> items = catalog.items().stream().map(CatalogMapper::toMap).toList();
        return new CatalogEntity(catalog.id(), catalog.tenantId(), catalog.declaredAttributes().toArray(String[]::new),
                items, catalog.createdAt(), catalog.updatedAt());
    }

    public Catalog toDomain(CatalogEntity entity) {
        List<CatalogItem> items = entity.getItems().stream().map(CatalogMapper::toItem).toList();
        return Catalog.rehydrate(entity.getId(), entity.getTenantId(), List.of(entity.getDeclaredAttributes()),
                items, entity.getCreatedAt(), entity.getUpdatedAt());
    }

    /** The stored (and hashed) shape of an item. */
    static Map<String, Object> toMap(CatalogItem item) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("ref", item.ref());
        map.put("section", item.section());
        map.put("name", item.name());
        if (item.description() != null) {
            map.put("description", item.description());
        }
        if (item.price() != null) {
            map.put("price", item.price().toPlainString());
        }
        map.put("attributes", item.attributes());
        return map;
    }

    @SuppressWarnings("unchecked")
    private static CatalogItem toItem(Map<String, Object> map) {
        Object price = map.get("price");
        return new CatalogItem((String) map.get("ref"), (String) map.get("section"), (String) map.get("name"),
                (String) map.get("description"), price == null ? null : new BigDecimal(price.toString()),
                (Map<String, Object>) map.getOrDefault("attributes", Map.of()));
    }
}
