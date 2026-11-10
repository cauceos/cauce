package dev.cauce.tools.business;

import dev.cauce.core.audit.CanonicalJson;
import dev.cauce.core.business.catalog.Catalog;
import dev.cauce.core.business.catalog.CatalogItem;
import dev.cauce.core.business.catalog.CatalogSource;
import dev.cauce.core.tool.ToolCall;
import dev.cauce.core.tool.ToolDefinition;
import dev.cauce.core.tool.ToolExecutionContext;
import dev.cauce.core.tool.ToolResult;
import dev.cauce.tools.spi.Tool;
import dev.cauce.tools.spi.ToolArguments.InvalidToolArgumentException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Shows catalog items to the person as product cards in the reply (ADR 0014, WC8). It accepts only
 * refs of the agent's tenant's catalog: each one becomes a card that stores the ref alone, and the
 * channel reads its name, price and button from the catalog when it shows it, never from the model's
 * text. A ref not in the catalog is refused by name, so the model learns it does not exist.
 */
public final class ShowProductsTool implements Tool {

    static final String NAME = "show_products";
    /** The most cards one call shows. */
    static final int MAX_REFS = 6;

    private static final ToolDefinition DEFINITION = new ToolDefinition(NAME,
            "Shows catalog items to the customer as product cards under your reply, each with its name, its price "
                    + "and a button to buy it, all read from the catalog. Pass the refs exactly as search_catalog "
                    + "returned them, at most " + MAX_REFS + "; refs that are not in the catalog are refused. "
                    + "Use it when you recommend or compare items; your reply still says in words why.",
            Map.of("type", "object",
                    "properties", Map.of(
                            "refs", Map.of("type", "array", "items", Map.of("type", "string"),
                                    "minItems", 1, "maxItems", MAX_REFS,
                                    "description", "Catalog refs of the items to show, in the order to show them.")),
                    "required", List.of("refs"),
                    "additionalProperties", false));

    private final Supplier<CatalogSource> catalogs;

    public ShowProductsTool(Supplier<CatalogSource> catalogs) {
        this.catalogs = catalogs;
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolResult execute(ToolCall call, ToolExecutionContext context) {
        List<String> refs = refsOf(call);
        CatalogSource source = catalogs.get();
        Optional<Catalog> catalog = source == null ? Optional.empty() : source.findCatalog(context.tenantId());
        if (catalog.isEmpty()) {
            return ToolResult.success(call.toolCallId(), NAME, CanonicalJson.serialize(Map.of(
                    "shown", List.of(), "not_in_catalog", refs,
                    "note", "No catalog is loaded: no card can be shown.")));
        }
        Map<String, CatalogItem> byRef = catalog.get().items().stream()
                .collect(Collectors.toMap(CatalogItem::ref, Function.identity(), (a, b) -> a));
        List<Map<String, Object>> shown = new ArrayList<>();
        List<String> cards = new ArrayList<>();
        List<String> refused = new ArrayList<>();
        for (String ref : refs) {
            CatalogItem item = byRef.get(ref);
            if (item == null) {
                refused.add(ref);
                continue;
            }
            cards.add(ref);
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("ref", ref);
            card.put("name", item.name());
            if (item.price() != null) {
                card.put("price", item.price().toPlainString());
            }
            shown.add(card);
        }
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("shown", shown);
        output.put("not_in_catalog", refused);
        output.put("note", cards.isEmpty()
                ? "No card is shown: none of the refs is in the catalog."
                : "These cards go under your reply. Do not list them again; say in words why they fit.");
        return ToolResult.showing(call.toolCallId(), NAME, CanonicalJson.serialize(output), cards);
    }

    private static List<String> refsOf(ToolCall call) {
        if (!(call.input().get("refs") instanceof List<?> raw) || raw.isEmpty()) {
            throw new InvalidToolArgumentException("refs is a non-empty list of catalog refs");
        }
        Set<String> refs = new LinkedHashSet<>();
        for (Object value : raw) {
            if (!(value instanceof String ref) || ref.isBlank()) {
                throw new InvalidToolArgumentException("each ref is a non-blank string");
            }
            refs.add(ref.strip());
        }
        if (refs.size() > MAX_REFS) {
            throw new InvalidToolArgumentException("refs holds at most " + MAX_REFS + " refs");
        }
        return List.copyOf(refs);
    }
}
