package dev.cauce.api.llm;

import dev.cauce.api.web.PageResponse;
import dev.cauce.llm.spi.LlmProviderRegistry;
import dev.cauce.tenancy.AgentService;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /v1/llm-providers}: each LLM provider an agent may name, and whether this instance
 * has it configured — so a client can tell "this agent's {@code model_provider} cannot answer
 * here" before sending a message instead of after it fails. An adapter registers only when
 * configured (its key set, or enabled), so {@code configured} is exactly "an invocation can
 * reach it".
 *
 * <p>Instance configuration, the same for every tenant and holding no tenant data: any valid
 * key reads it. The name and the yes/no only — never a base URL, a key, nor any other setting.
 * The uniform list envelope, as one page: the list is a handful of ids, never paginated.
 */
@RestController
public class LlmProviderController {

    private final LlmProviderRegistry registry;
    private final AgentService agentService;

    public LlmProviderController(LlmProviderRegistry registry, AgentService agentService) {
        this.registry = registry;
        this.agentService = agentService;
    }

    @GetMapping("/v1/llm-providers")
    public PageResponse<LlmProviderResponse> list() {
        Set<String> configured = registry.availableProviders();
        Set<String> ids = new TreeSet<>(agentService.supportedProviders());
        ids.addAll(configured);
        return new PageResponse<>(ids.stream()
                .map(id -> new LlmProviderResponse(id, configured.contains(id)))
                .toList(), null);
    }

    /** One provider: the id an agent's {@code model_provider} names it by, and whether it is configured. */
    public record LlmProviderResponse(String id, boolean configured) {
    }
}
