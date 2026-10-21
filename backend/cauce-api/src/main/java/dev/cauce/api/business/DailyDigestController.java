package dev.cauce.api.business;

import dev.cauce.api.web.PageParams;
import dev.cauce.api.web.PageResponse;
import dev.cauce.business.digest.DailyDigestService;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The owner's nightly summaries (ADR 0007, B10): their history, and today's on demand. Running
 * today's is idempotent: the first run of a local day composes and queues it, every later one
 * returns that same summary. A business without a profile is a 404 (no time zone to have a day in).
 */
@RestController
public class DailyDigestController {

    private final DailyDigestService digestService;

    public DailyDigestController(DailyDigestService digestService) {
        this.digestService = digestService;
    }

    @GetMapping("/v1/tenants/{tenantId}/daily-digests")
    public PageResponse<DailyDigestResponse> list(@PathVariable UUID tenantId,
                                                  @RequestParam(defaultValue = "50") int limit,
                                                  @RequestParam(required = false) String cursor) {
        PageParams page = PageParams.parse(limit, cursor);
        List<DailyDigestResponse> fetched = digestService.listForTenant(tenantId, page.afterId(), page.fetchSize())
                .stream().map(DailyDigestResponse::from).toList();
        return PageResponse.of(fetched, page.limit(), DailyDigestResponse::id);
    }

    /** Today's summary of the business: made and queued now if it has none yet. */
    @PostMapping("/v1/tenants/{tenantId}/daily-digests")
    public DailyDigestResponse runToday(@PathVariable UUID tenantId) {
        return DailyDigestResponse.from(digestService.runToday(tenantId));
    }
}
