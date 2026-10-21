package dev.cauce.api.agent;

import dev.cauce.core.agent.Agent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code PATCH /v1/agents/{id}}: the agent's editable configuration. Every field is
 * optional; an absent (or null) field keeps its current value. {@code name} and
 * {@code fallbackReply}, when present, must not be blank.
 */
public record UpdateAgentRequest(
        @Pattern(regexp = NOT_BLANK, message = "must not be blank") String name,
        String systemPrompt,
        @Pattern(regexp = NOT_BLANK, message = "must not be blank")
        @Size(max = Agent.MAX_FALLBACK_REPLY_LENGTH) String fallbackReply) {

    /** At least one non-whitespace character, across lines. */
    static final String NOT_BLANK = "(?s).*[^\\s].*";
}
