package dev.cauce.api.agent;

import dev.cauce.api.web.PageParams;
import dev.cauce.api.web.PageResponse;
import dev.cauce.core.agent.AgentNotFoundException;
import dev.cauce.tenancy.AgentService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface for agents. Thin: validates and deserialises the request, delegates to
 * {@link AgentService} (which enforces the CLIENT-tier rule, provider support, and RLS), and
 * maps the domain result to an {@link AgentResponse}. Tenant context is derived from the
 * validated API key by {@code ApiKeyAuthenticationFilter} (the {@code Authorization: Bearer}
 * principal).
 */
@RestController
public class AgentController {

    private final AgentService agentService;

    public AgentController(AgentService agentService) {
        this.agentService = agentService;
    }

    @PostMapping("/v1/tenants/{tenantId}/agents")
    public ResponseEntity<AgentResponse> create(@PathVariable UUID tenantId,
                                                @Valid @RequestBody CreateAgentRequest request) {
        var agent = agentService.createAgent(tenantId, request.name(), request.systemPrompt(),
                request.modelProvider(), request.modelName(), request.temperature(), request.maxResponseTokens(),
                request.fallbackReply(), request.tools() == null ? List.of() : request.tools());
        return ResponseEntity.created(URI.create("/v1/agents/" + agent.id())).body(AgentResponse.from(agent));
    }

    /**
     * Replaces the tools the agent may use (ADR 0005, C4). Every name must be a registered tool
     * ({@code 422 unknown_tool} otherwise); an agent outside the caller's scope is a 404.
     */
    @PutMapping("/v1/agents/{id}/tools")
    public AgentResponse setTools(@PathVariable UUID id, @Valid @RequestBody UpdateAgentToolsRequest request) {
        return AgentResponse.from(agentService.setTools(id, request.tools()));
    }

    /**
     * Edits the agent's name, system prompt, fallback reply, whether it elides past lookups and its
     * prompt cache retention; an absent field is unchanged. An agent outside the caller's scope is a 404.
     */
    @PatchMapping("/v1/agents/{id}")
    public AgentResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateAgentRequest request) {
        return AgentResponse.from(agentService.updateAgent(id, request.name(), request.systemPrompt(),
                request.fallbackReply(), request.elidePastLookups(), request.promptCacheRetention()));
    }

    @GetMapping("/v1/agents/{id}")
    public AgentResponse get(@PathVariable UUID id) {
        return AgentResponse.from(agentService.getAgent(id).orElseThrow(() ->
                new AgentNotFoundException("No agent found for id " + id)));
    }

    @GetMapping("/v1/tenants/{tenantId}/agents")
    public PageResponse<AgentResponse> list(@PathVariable UUID tenantId,
                                            @RequestParam(defaultValue = "50") int limit,
                                            @RequestParam(required = false) String cursor) {
        PageParams page = PageParams.parse(limit, cursor);
        List<AgentResponse> fetched = agentService
                .listAgentsForTenant(tenantId, page.afterId(), page.fetchSize())
                .stream().map(AgentResponse::from).toList();
        return PageResponse.of(fetched, page.limit(), AgentResponse::id);
    }
}
