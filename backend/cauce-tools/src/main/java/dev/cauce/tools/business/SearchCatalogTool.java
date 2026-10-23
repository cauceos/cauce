package dev.cauce.tools.business;

import dev.cauce.core.audit.CanonicalJson;
import dev.cauce.core.business.catalog.Catalog;
import dev.cauce.core.business.catalog.Catalog.SearchResult;
import dev.cauce.core.business.catalog.CatalogItem;
import dev.cauce.core.business.catalog.CatalogSource;
import dev.cauce.core.tool.ToolCall;
import dev.cauce.core.tool.ToolDefinition;
import dev.cauce.core.tool.ToolExecutionContext;
import dev.cauce.core.tool.ToolResult;
import dev.cauce.tools.spi.Tool;
import dev.cauce.tools.spi.ToolArguments;
import dev.cauce.tools.spi.ToolArguments.InvalidToolArgumentException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Searches the catalog of the agent's tenant (ADR 0007, B4) and returns the items exactly as the
 * business stated them. For each declared attribute an item does not state, the value is
 * {@link Catalog#NOT_DECLARED} — explicit, so that the model cannot read an omission as "none".
 * The answer of the agent about an item is grounded in this call, which the audit chain records.
 */
public final class SearchCatalogTool implements Tool {

    static final String NAME = "search_catalog";
    /** The most items one result carries; a larger result says it was cut. */
    static final int MAX_ITEMS = 100;

    private static final ToolDefinition DEFINITION = new ToolDefinition(NAME,
            "Searches the business catalog (menu, services, products) and returns the matching items exactly "
                    + "as the business stated them, with price and attributes. Call it in the same turn before "
                    + "saying anything about an item, its price or an attribute such as allergens. Only items "
                    + "returned here exist. An attribute whose value is NOT_DECLARED is not stated by the "
                    + "business: say it is not stated, never guess. With without_attribute and without_value, "
                    + "only items that state the attribute and do not contain the value are returned; items "
                    + "that do not state it are listed under 'undetermined' and cannot be recommended as safe.",
            Map.of("type", "object",
                    "properties", Map.of(
                            "query", Map.of("type", "string",
                                    "description", "Words to look for, such as an item name. Omit to list everything."),
                            "section", Map.of("type", "string",
                                    "description", "Only items of this section of the catalog."),
                            "without_attribute", Map.of("type", "string",
                                    "description", "Attribute to filter on, e.g. allergens."),
                            "without_value", Map.of("type", "string",
                                    "description", "Value the item must not contain, e.g. gluten.")),
                    "additionalProperties", false));

    private final Supplier<CatalogSource> catalogs;

    public SearchCatalogTool(Supplier<CatalogSource> catalogs) {
        this.catalogs = catalogs;
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public boolean lookup() {
        return true;
    }

    @Override
    public ToolResult execute(ToolCall call, ToolExecutionContext context) {
        ToolArguments args = ToolArguments.of(call);
        CatalogSource source = catalogs.get();
        Optional<Catalog> catalog = source == null ? Optional.empty() : source.findCatalog(context.tenantId());
        if (catalog.isEmpty()) {
            return ToolResult.success(call.toolCallId(), NAME, CanonicalJson.serialize(Map.of(
                    "available", false,
                    "note", "No catalog is loaded: no item, price or attribute is known.")));
        }
        Optional<String> withoutAttribute = args.text("without_attribute");
        Optional<String> withoutValue = args.text("without_value");
        if (withoutAttribute.isPresent() != withoutValue.isPresent()) {
            throw new InvalidToolArgumentException("without_attribute and without_value go together");
        }
        SearchResult result = catalog.get().search(args.text("query").orElse(null), args.text("section").orElse(null),
                withoutAttribute.orElse(null), withoutValue.orElse(null));
        List<CatalogItem> shown = result.matches().stream().limit(MAX_ITEMS).toList();
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("available", true);
        output.put("declared_attributes", catalog.get().declaredAttributes());
        output.put("sections", catalog.get().sections());
        output.put("items", shown.stream().map(item -> describe(catalog.get(), item)).toList());
        output.put("undetermined", result.undetermined().stream().map(item -> describe(catalog.get(), item)).toList());
        if (shown.size() < result.matches().size()) {
            output.put("truncated", true);
        }
        return ToolResult.success(call.toolCallId(), NAME, CanonicalJson.serialize(output));
    }

    private static Map<String, Object> describe(Catalog catalog, CatalogItem item) {
        Map<String, Object> attributes = new LinkedHashMap<>(item.attributes());
        catalog.declaredAttributes().forEach(name ->
                catalog.statedAttribute(item, name).ifPresent(value -> attributes.put(name, value)));
        Map<String, Object> described = new LinkedHashMap<>();
        described.put("ref", item.ref());
        described.put("section", item.section());
        described.put("name", item.name());
        if (item.description() != null) {
            described.put("description", item.description());
        }
        if (item.price() != null) {
            described.put("price", item.price().toPlainString());
        }
        described.put("attributes", attributes);
        return described;
    }
}
