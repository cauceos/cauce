package dev.cauce.llm.model;

import java.util.Objects;

/**
 * A single message in the neutral model: a role and its text content. Immutable.
 * Content is preserved verbatim and must not be null (it may be empty only for roles
 * where that is meaningful; callers normally pass non-empty text).
 */
public record LlmMessage(LlmRole role, String content) {

    public LlmMessage {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(content, "content");
    }

    public static LlmMessage user(String content) {
        return new LlmMessage(LlmRole.USER, content);
    }

    public static LlmMessage assistant(String content) {
        return new LlmMessage(LlmRole.ASSISTANT, content);
    }

    public static LlmMessage system(String content) {
        return new LlmMessage(LlmRole.SYSTEM, content);
    }
}
