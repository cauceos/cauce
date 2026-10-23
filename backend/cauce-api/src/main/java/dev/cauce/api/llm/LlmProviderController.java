package dev.cauce.api.llm;

import dev.cauce.api.web.PageResponse;
import dev.cauce.llm.spi.LlmProviderRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /v1/llm-providers}: the LLM providers this instance can call, so a client can tell
 * "this agent's {@code model_provider} is not configured here" before sending a message instead
 * of after it fails. An adapter registers only when configured (its key set, or enabled), so the
 * list is exactly what an invocation can reach.
 *
 * <p>Instance configuration, the same for every tenant and holding no tenant data: any valid
 * key reads it. The ids only — never a key, a base URL or any other setting. The uniform list
 * envelope, as one page: the list is a handful of ids, never paginated.
 */
@RestController
public class LlmProviderController {

    private final LlmProviderRegistry registry;

    public LlmProviderController(LlmProviderRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/v1/llm-providers")
    public PageResponse<LlmProviderResponse> list() {
        return new PageResponse<>(registry.availableProviders().stream()
                .sorted()
                .map(LlmProviderResponse::new)
                .toList(), null);
    }

    /** One configured provider: the id an agent's {@code model_provider} names it by. */
    public record LlmProviderResponse(String id) {
    }
}
