package dev.cauce.llm.anthropic;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Matches only when {@code ANTHROPIC_API_KEY} is present and non-blank in the environment.
 * Combined with the enabled flag, this ensures the provider bean exists only when it could
 * actually authenticate, so the registry advertises Anthropic exclusively when usable.
 */
class AnthropicApiKeyCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String apiKey = context.getEnvironment().getProperty("ANTHROPIC_API_KEY");
        return apiKey != null && !apiKey.isBlank();
    }
}
