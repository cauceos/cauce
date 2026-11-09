package dev.cauce.tools.business;

import dev.cauce.core.audit.CanonicalJson;
import dev.cauce.core.business.handoff.Handoff;
import dev.cauce.core.business.handoff.HandoffSink;
import dev.cauce.core.business.profile.BusinessProfile;
import dev.cauce.core.business.profile.BusinessProfileSource;
import dev.cauce.core.tool.ToolCall;
import dev.cauce.core.tool.ToolDefinition;
import dev.cauce.core.tool.ToolExecutionContext;
import dev.cauce.core.tool.ToolResult;
import dev.cauce.tools.spi.Tool;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Lists the cases the person handed to the owner that are still open (ADR 0013, T1), whichever of
 * their conversations each came from, so nothing depends on an earlier thread's history. The
 * person is the identity of the execution context, never an argument. A lookup: it changes nothing.
 */
public final class ListMyCasesTool implements Tool {

    static final String NAME = "list_my_cases";
    static final String NEXT_STEP = "These cases are with the owner. Do not say when the owner will answer or "
            + "what they will decide.";

    private static final ToolDefinition DEFINITION = new ToolDefinition(NAME,
            "Lists the open cases of the person you are talking to that were handed to the owner (groups, events, "
                    + "complaints, special requests), with their codes, even if they were opened in an earlier "
                    + "conversation. Use it whenever the person asks about something they asked for before. "
                    + "Takes no arguments.",
            Map.of("type", "object", "properties", Map.of(), "additionalProperties", false));

    private final Supplier<HandoffSink> sinks;
    private final Supplier<BusinessProfileSource> profiles;

    /** {@code sinks} and {@code profiles} supply their port, or null when there is none. */
    public ListMyCasesTool(Supplier<HandoffSink> sinks, Supplier<BusinessProfileSource> profiles) {
        this.sinks = sinks;
        this.profiles = profiles;
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
        HandoffSink sink = sinks.get();
        if (sink == null) {
            return ToolResult.error(call.toolCallId(), NAME, "Cases cannot be read here.");
        }
        ZoneId zone = zoneOf(context);
        List<Map<String, Object>> cases = sink.openForIdentity(context.tenantId(), context.identityId()).stream()
                .map(handoff -> view(handoff, zone)).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("cases", cases);
        if (!cases.isEmpty()) {
            out.put("next_step", NEXT_STEP);
        }
        return ToolResult.success(call.toolCallId(), NAME, CanonicalJson.serialize(out));
    }

    /** The business's zone, so the date a case was opened is the business's day; UTC without a profile. */
    private ZoneId zoneOf(ToolExecutionContext context) {
        BusinessProfileSource source = profiles.get();
        Optional<BusinessProfile> profile = source == null ? Optional.empty() : source.findProfile(context.tenantId());
        return profile.map(BusinessProfile::timeZone).orElse(ZoneOffset.UTC);
    }

    private static Map<String, Object> view(Handoff handoff, ZoneId zone) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("code", handoff.code());
        view.put("category", handoff.category());
        view.put("summary", handoff.summary());
        view.put("opened_on", handoff.createdAt().atZone(zone).toLocalDate().toString());
        return view;
    }
}
