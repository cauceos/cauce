package dev.cauce.api.agent;

import dev.cauce.core.agent.Agent;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Request body for {@code POST /v1/tenants/{tenantId}/agents}.
 *
 * <p>{@code temperature} and {@code maxResponseTokens} are optional. A null temperature stays
 * unset and is not sent to the model (the provider's own default applies); a null
 * {@code maxResponseTokens} takes the domain default. When present they are bounded to the
 * domain's valid ranges
 * ({@code temperature} in [0.0, 1.0], {@code maxResponseTokens} positive). {@code modelProvider}
 * is only checked for non-blankness here; the set of supported providers is validated by the
 * service (it will move to the cauce-llm SPI), keeping provider knowledge out of the DTO.
 * {@code fallbackReply} and {@code unsupportedMessageReply} are optional: null takes the platform's
 * text (ADR 0005, C3; ADR 0006, W5).
 */
public record CreateAgentRequest(
        @NotBlank String name,
        @NotBlank String systemPrompt,
        @NotBlank String modelProvider,
        @NotBlank String modelName,
        @DecimalMin("0.0") @DecimalMax("1.0") Double temperature,
        @Positive Integer maxResponseTokens,
        @Size(max = Agent.MAX_FALLBACK_REPLY_LENGTH) String fallbackReply,
        List<@NotBlank String> tools,
        @Size(max = Agent.MAX_FALLBACK_REPLY_LENGTH) String unsupportedMessageReply) {

    /** A request that leaves the reply to an unsupported message to the platform. */
    public CreateAgentRequest(String name, String systemPrompt, String modelProvider, String modelName,
                              Double temperature, Integer maxResponseTokens, String fallbackReply,
                              List<String> tools) {
        this(name, systemPrompt, modelProvider, modelName, temperature, maxResponseTokens, fallbackReply, tools,
                null);
    }
}
