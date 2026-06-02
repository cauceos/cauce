package dev.cauce.api.agent;

import dev.cauce.core.agent.AgentNotFoundException;
import dev.cauce.tenancy.AgentService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
                request.modelProvider(), request.modelName(), request.temperature(), request.maxResponseTokens());
        return ResponseEntity.created(URI.create("/v1/agents/" + agent.id())).body(AgentResponse.from(agent));
    }

    @GetMapping("/v1/agents/{id}")
    public AgentResponse get(@PathVariable UUID id) {
        return AgentResponse.from(agentService.getAgent(id).orElseThrow(() ->
                new AgentNotFoundException("No agent found for id " + id)));
    }

    // TODO: paginate once agent sets can grow large; returns all agents of the tenant for now.
    @GetMapping("/v1/tenants/{tenantId}/agents")
    public List<AgentResponse> list(@PathVariable UUID tenantId) {
        return agentService.listAgentsForTenant(tenantId).stream().map(AgentResponse::from).toList();
    }
}
