package dev.cauce.api.invocation;

import dev.cauce.orchestration.PendingInvocation;
import dev.cauce.orchestration.PendingInvocationNotFoundException;
import dev.cauce.orchestration.PendingInvocationService;
import dev.cauce.orchestration.usage.LlmUsageQueryService;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface for invocation status. The invocation id arrives in the {@code 202 Accepted}
 * body of {@code POST /v1/agents/{agentId}/messages}; clients poll this endpoint to track
 * the asynchronous processing (the reply itself is read from the conversation messages).
 * Rows survive their terminal states, so a completed or failed invocation stays readable.
 * Tenant context is derived from the validated API key; RLS scopes the row, so an
 * invocation outside the caller's hierarchy is indistinguishable from a nonexistent one.
 *
 * <p>The invocation is resolved first and the usage read only afterwards: the 404 for an
 * out-of-scope invocation is decided by the invocation row alone, so the usage query can
 * never become a side channel about what exists elsewhere. It is scoped by the same RLS
 * policy regardless.
 */
@RestController
public class InvocationController {

    private final PendingInvocationService pendingInvocationService;
    private final LlmUsageQueryService usageQueryService;

    public InvocationController(PendingInvocationService pendingInvocationService,
                                LlmUsageQueryService usageQueryService) {
        this.pendingInvocationService = pendingInvocationService;
        this.usageQueryService = usageQueryService;
    }

    @GetMapping("/v1/invocations/{id}")
    public InvocationResponse get(@PathVariable UUID id) {
        PendingInvocation invocation = pendingInvocationService.getPendingInvocation(id)
                .orElseThrow(() -> new PendingInvocationNotFoundException(
                        "No invocation found for id " + id));
        return InvocationResponse.from(invocation, usageQueryService.findByInvocation(id));
    }
}
