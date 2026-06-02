package dev.cauce.llm.openai;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Matches only when {@code MISTRAL_API_KEY} is present and non-blank in the environment. Combined
 * with the enabled flag, this ensures the Mistral provider bean exists only when it could actually
 * authenticate, so the registry advertises {@code mistral} exclusively when usable.
 */
class MistralApiKeyCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String apiKey = context.getEnvironment().getProperty("MISTRAL_API_KEY");
        return apiKey != null && !apiKey.isBlank();
    }
}
