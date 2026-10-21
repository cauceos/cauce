package dev.cauce.api.agent;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * Body of {@code PUT /v1/agents/{id}/tools}: the complete list of tools the agent may use
 * (ADR 0005, C4). It replaces the previous grant; an empty list revokes every tool.
 */
public record UpdateAgentToolsRequest(@NotNull List<@NotBlank String> tools) {
}
