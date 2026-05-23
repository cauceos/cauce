package dev.cauce.llm.model;

import dev.cauce.llm.spi.LlmCredential;
import java.util.List;
import java.util.Objects;

/**
 * A provider-independent request to generate a completion. Immutable; build via
 * {@link #builder()}.
 *
 * <p>{@code systemPrompt}, {@code maxTokens}, and {@code temperature} are optional and may
 * be null (adapters apply provider defaults). {@code tools} is part of the contract from
 * v1.0 but is always empty until the Tool entity exists.
 */
public record LlmInvocation(
        String modelName,
        List<LlmMessage> messages,
        String systemPrompt,
        List<ToolDefinition> tools,
        Integer maxTokens,
        Double temperature,
        LlmCredential credential) {

    public LlmInvocation {
        if (modelName == null || modelName.isBlank()) {
            throw new IllegalArgumentException("modelName must not be blank");
        }
        messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
        tools = tools == null ? List.of() : List.copyOf(tools);
        Objects.requireNonNull(credential, "credential");
        if (maxTokens != null && maxTokens <= 0) {
            throw new IllegalArgumentException("maxTokens must be positive when set");
        }
        if (temperature != null && (temperature < 0.0 || temperature > 1.0)) {
            throw new IllegalArgumentException("temperature must be within [0.0, 1.0] when set");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Fluent builder for {@link LlmInvocation}. */
    public static final class Builder {
        private String modelName;
        private List<LlmMessage> messages = List.of();
        private String systemPrompt;
        private List<ToolDefinition> tools = List.of();
        private Integer maxTokens;
        private Double temperature;
        private LlmCredential credential;

        private Builder() {
        }

        public Builder modelName(String modelName) {
            this.modelName = modelName;
            return this;
        }

        public Builder messages(List<LlmMessage> messages) {
            this.messages = messages;
            return this;
        }

        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        public Builder tools(List<ToolDefinition> tools) {
            this.tools = tools;
            return this;
        }

        public Builder maxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        public Builder temperature(Double temperature) {
            this.temperature = temperature;
            return this;
        }

        public Builder credential(LlmCredential credential) {
            this.credential = credential;
            return this;
        }

        public LlmInvocation build() {
            return new LlmInvocation(
                    modelName, messages, systemPrompt, tools, maxTokens, temperature, credential);
        }
    }
}
