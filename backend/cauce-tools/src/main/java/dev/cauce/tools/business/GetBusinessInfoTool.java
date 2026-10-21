package dev.cauce.tools.business;

import dev.cauce.core.audit.CanonicalJson;
import dev.cauce.core.business.profile.BusinessProfile;
import dev.cauce.core.business.profile.BusinessProfileSource;
import dev.cauce.core.tool.ToolCall;
import dev.cauce.core.tool.ToolDefinition;
import dev.cauce.core.tool.ToolExecutionContext;
import dev.cauce.core.tool.ToolResult;
import dev.cauce.tools.spi.Tool;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Reads the business profile of the agent's tenant (ADR 0007, B4): what the business stated
 * about itself, and nothing else. The answer of the agent is grounded in this call, which the
 * audit chain records (ADR 0005, C5).
 */
public final class GetBusinessInfoTool implements Tool {

    static final String NAME = "get_business_info";

    private static final ToolDefinition DEFINITION = new ToolDefinition(NAME,
            "Returns what the business has stated about itself: its name, time zone and facts such as opening "
                    + "hours, address, services and policies. Call it before answering any question about the "
                    + "business. Anything not in the facts is not known: say so, never guess, and record the "
                    + "question with record_unanswered_question. Takes no arguments.",
            Map.of("type", "object", "properties", Map.of(), "additionalProperties", false));

    private final Supplier<BusinessProfileSource> profiles;

    public GetBusinessInfoTool(Supplier<BusinessProfileSource> profiles) {
        this.profiles = profiles;
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolResult execute(ToolCall call, ToolExecutionContext context) {
        BusinessProfileSource source = profiles.get();
        Optional<BusinessProfile> profile = source == null ? Optional.empty() : source.findProfile(context.tenantId());
        if (profile.isEmpty()) {
            return ToolResult.success(call.toolCallId(), NAME, CanonicalJson.serialize(Map.of(
                    "available", false,
                    "note", "No business information is loaded: nothing about the business is known.")));
        }
        Map<String, Object> facts = new LinkedHashMap<>();
        profile.get().facts().forEach(fact -> facts.put(fact.key(), fact.value()));
        return ToolResult.success(call.toolCallId(), NAME, CanonicalJson.serialize(Map.of(
                "available", true,
                "name", profile.get().name(),
                "time_zone", profile.get().timeZone().getId(),
                "facts", facts)));
    }
}
