package dev.cauce.tools.clock;

import dev.cauce.core.audit.CanonicalJson;
import dev.cauce.core.business.profile.BusinessProfile;
import dev.cauce.core.business.profile.BusinessProfileSource;
import dev.cauce.core.tool.ToolCall;
import dev.cauce.core.tool.ToolDefinition;
import dev.cauce.core.tool.ToolExecutionContext;
import dev.cauce.core.tool.ToolResult;
import dev.cauce.tools.spi.Tool;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Built-in tool that returns the current time. Takes no arguments.
 *
 * <p>Without a business profile it returns the instant in ISO-8601 (UTC). When the agent's
 * business has a profile (ADR 0007), it also returns the local date, time and weekday in the
 * business's time zone — what "tomorrow" and "on Friday" are measured against.
 *
 * <p>The {@link Clock} is injected so the tool is deterministic under test (a fixed clock).
 */
public final class ClockTool implements Tool {

    static final String NAME = "get_current_time";

    private static final ToolDefinition DEFINITION = new ToolDefinition(
            NAME,
            "Returns the current date and time: the UTC instant and, when the business has a time zone, the "
                    + "local date, time and weekday where the business is. Takes no arguments.",
            Map.of("type", "object", "properties", Map.of(), "additionalProperties", false));

    private final Clock clock;
    private final Supplier<BusinessProfileSource> profiles;

    /** A clock that knows no business: it always answers in UTC. */
    public ClockTool(Clock clock) {
        this(clock, () -> null);
    }

    /** {@code profiles} supplies the profile port, or null when there is none. */
    public ClockTool(Clock clock, Supplier<BusinessProfileSource> profiles) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolResult execute(ToolCall call, ToolExecutionContext context) {
        Objects.requireNonNull(call, "call");
        Instant now = Instant.now(clock);
        BusinessProfileSource source = profiles.get();
        Optional<BusinessProfile> profile = source == null ? Optional.empty() : source.findProfile(context.tenantId());
        if (profile.isEmpty()) {
            return ToolResult.success(call.toolCallId(), NAME, now.toString());
        }
        LocalDateTime local = LocalDateTime.ofInstant(now, profile.get().timeZone()).truncatedTo(ChronoUnit.SECONDS);
        return ToolResult.success(call.toolCallId(), NAME, CanonicalJson.serialize(Map.of(
                "utc", now.toString(),
                "time_zone", profile.get().timeZone().getId(),
                "local_date", local.toLocalDate().toString(),
                "local_time", local.toLocalTime().toString(),
                "weekday", local.getDayOfWeek().name().toLowerCase(Locale.ROOT))));
    }
}
