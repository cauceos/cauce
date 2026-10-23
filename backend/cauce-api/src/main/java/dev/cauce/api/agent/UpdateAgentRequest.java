package dev.cauce.api.agent;

import dev.cauce.core.agent.Agent;
import dev.cauce.core.agent.PromptCacheRetention;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code PATCH /v1/agents/{id}}: the agent's editable configuration. Every field is
 * optional; an absent (or null) field keeps its current value. {@code name} and
 * {@code fallbackReply}, when present, must not be blank. {@code elidePastLookups} turns on or off
 * leaving earlier turns' lookup results out of the model's context (deferred R1).
 * {@code promptCacheRetention} is {@code SHORT} or {@code LONG}: how long the provider keeps the
 * agent's prompt prefix cached (ADR 0010, S9).
 */
public record UpdateAgentRequest(
        @Pattern(regexp = NOT_BLANK, message = "must not be blank") String name,
        String systemPrompt,
        @Pattern(regexp = NOT_BLANK, message = "must not be blank")
        @Size(max = Agent.MAX_FALLBACK_REPLY_LENGTH) String fallbackReply,
        Boolean elidePastLookups,
        PromptCacheRetention promptCacheRetention) {

    /** At least one non-whitespace character, across lines. */
    static final String NOT_BLANK = "(?s).*[^\\s].*";
}
