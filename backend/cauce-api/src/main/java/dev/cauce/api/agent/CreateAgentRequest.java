package dev.cauce.api.agent;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * Request body for {@code POST /v1/tenants/{tenantId}/agents}.
 *
 * <p>{@code temperature} and {@code maxResponseTokens} are optional: when null, the domain
 * applies its defaults. When present they are bounded to the domain's valid ranges
 * ({@code temperature} in [0.0, 1.0], {@code maxResponseTokens} positive). {@code modelProvider}
 * is only checked for non-blankness here; the set of supported providers is validated by the
 * service (it will move to the cauce-llm SPI), keeping provider knowledge out of the DTO.
 */
public record CreateAgentRequest(
        @NotBlank String name,
        @NotBlank String systemPrompt,
        @NotBlank String modelProvider,
        @NotBlank String modelName,
        @DecimalMin("0.0") @DecimalMax("1.0") Double temperature,
        @Positive Integer maxResponseTokens) {
}
