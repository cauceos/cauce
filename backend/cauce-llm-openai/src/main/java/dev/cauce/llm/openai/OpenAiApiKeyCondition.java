package dev.cauce.llm.openai;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Matches only when {@code OPENAI_API_KEY} is present and non-blank in the environment. Combined
 * with the enabled flag, this ensures the OpenAI provider bean exists only when it could actually
 * authenticate, so the registry advertises {@code openai} exclusively when usable.
 */
class OpenAiApiKeyCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String apiKey = context.getEnvironment().getProperty("OPENAI_API_KEY");
        return apiKey != null && !apiKey.isBlank();
    }
}
