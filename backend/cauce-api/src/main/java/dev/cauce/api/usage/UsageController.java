package dev.cauce.api.usage;

import dev.cauce.orchestration.usage.LlmUsageQueryService;
import dev.cauce.orchestration.usage.UsageReport.Granularity;
import dev.cauce.orchestration.usage.UsageReport.Grouping;
import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The usage of a tenant and its descendants (ADR 0010, S3): tokens, calls, conversations and cost,
 * per day or month, per tenant or agent. {@code from} and {@code to} are inclusive ISO dates, by
 * default the current month to today; {@code time_zone} cuts days and months, UTC by default so a
 * month here is the month of the spend limit. A tenant outside the caller's hierarchy is a 404.
 */
@RestController
public class UsageController {

    private final LlmUsageQueryService usageQueryService;

    public UsageController(LlmUsageQueryService usageQueryService) {
        this.usageQueryService = usageQueryService;
    }

    @GetMapping("/v1/tenants/{id}/usage")
    public UsageResponse usage(@PathVariable UUID id,
                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                               LocalDate from,
                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                               LocalDate to,
                               @RequestParam(defaultValue = "day") String granularity,
                               @RequestParam(name = "group_by", defaultValue = "tenant") String groupBy,
                               @RequestParam(name = "time_zone", defaultValue = "UTC") String timeZone) {
        return UsageResponse.from(usageQueryService.usage(id, from, to,
                parse(Granularity.class, "granularity", granularity), parse(Grouping.class, "group_by", groupBy),
                timeZone));
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String name, String value) {
        try {
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid " + name + " '" + value + "'", e);
        }
    }
}
