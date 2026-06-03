package dev.cauce.orchestration.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.cauce.core.message.Message;
import dev.cauce.core.message.MessageRole;
import dev.cauce.llm.model.LlmMessage;
import dev.cauce.llm.model.LlmRole;
import dev.cauce.orchestration.exception.MessageTooLargeForContextException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ContextBuilderTest {

    private static final String MODEL = "claude-sonnet-4-7"; // 200_000 window
    private static final int EFFECTIVE_WINDOW = 200_000 - ContextBuilder.RESERVED_FOR_RESPONSE; // 190_000
    private static final String SYSTEM_PROMPT = "You are a helpful assistant.";
    private static final UUID CONVERSATION = UUID.randomUUID();

    private final ContextBuilder builder = new ContextBuilder();

    /** A USER message whose content has exactly {@code chars} characters, tagged for identity. */
    private static Message userMessageOfLength(String tag, int chars) {
        String prefix = tag + ":";
        String content = prefix + "x".repeat(chars - prefix.length());
        return Message.from(CONVERSATION, MessageRole.USER, content);
    }

    @Test
    void build_whenShortConversation_includesAllInChronologicalOrder() {
        List<Message> messages = List.of(
                Message.from(CONVERSATION, MessageRole.USER, "Hello"),
                Message.from(CONVERSATION, MessageRole.AGENT, "Hi, how can I help?"),
                Message.from(CONVERSATION, MessageRole.USER, "I need an appointment"),
                Message.from(CONVERSATION, MessageRole.AGENT, "Sure, which day?"),
                Message.from(CONVERSATION, MessageRole.USER, "Monday"));

        LlmInvocationContext context = builder.build(messages, MODEL, SYSTEM_PROMPT);

        assertThat(context.messages()).hasSize(5);
        assertThat(context.messages()).extracting(LlmMessage::content)
                .containsExactly("Hello", "Hi, how can I help?", "I need an appointment",
                        "Sure, which day?", "Monday");
        int expected = TokenEstimator.estimate("Hello")
                + TokenEstimator.estimate("Hi, how can I help?")
                + TokenEstimator.estimate("I need an appointment")
                + TokenEstimator.estimate("Sure, which day?")
                + TokenEstimator.estimate("Monday");
        assertThat(context.estimatedTokens()).isEqualTo(expected);
        assertThat(context.contextWindowLimit()).isEqualTo(200_000);
        assertThat(context.reservedForResponse()).isEqualTo(10_000);
        assertThat(context.systemPrompt()).isEqualTo(SYSTEM_PROMPT);
    }

    @Test
    void build_whenConversationExceedsWindow_keepsMostRecentSliceChronological() {
        // 100 messages of 7000 chars each => 2000 tokens each. 190_000 / 2000 = 95 fit exactly.
        List<Message> messages = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            messages.add(userMessageOfLength("MSG" + i, 7000));
        }

        LlmInvocationContext context = builder.build(messages, MODEL, SYSTEM_PROMPT);

        assertThat(context.messages()).hasSize(95);
        assertThat(context.estimatedTokens()).isEqualTo(EFFECTIVE_WINDOW); // 190_000
        assertThat(context.estimatedTokens()).isLessThanOrEqualTo(EFFECTIVE_WINDOW);
        // The retained slice is the newest 95 (indices 5..99), in chronological order.
        assertThat(context.messages().get(0).content()).startsWith("MSG5:");
        assertThat(context.messages().get(94).content()).startsWith("MSG99:");
    }

    @Test
    void build_whenMostRecentMessageAloneExceedsWindow_throwsMessageTooLarge() {
        // 700_000 chars => ceil(700000/3.5) = 200_000 tokens > 190_000 effective window.
        Message huge = userMessageOfLength("HUGE", 700_000);

        assertThatThrownBy(() -> builder.build(List.of(huge), MODEL, SYSTEM_PROMPT))
                .isInstanceOf(MessageTooLargeForContextException.class)
                .hasMessageContaining("200000")
                .hasMessageContaining(String.valueOf(EFFECTIVE_WINDOW));
    }

    @Test
    void build_whenMostRecentMessageExactlyFillsWindow_isIncluded() {
        // 665_000 chars => ceil(665000/3.5) = 190_000 tokens == effective window (boundary).
        Message exact = userMessageOfLength("EXACT", 665_000);

        LlmInvocationContext context = builder.build(List.of(exact), MODEL, SYSTEM_PROMPT);

        assertThat(context.messages()).hasSize(1);
        assertThat(context.estimatedTokens()).isEqualTo(EFFECTIVE_WINDOW);
    }

    @Test
    void build_whenMixedRoles_mapsEachToCorrectLlmRole() {
        List<Message> messages = List.of(
                Message.from(CONVERSATION, MessageRole.USER, "user text"),
                Message.from(CONVERSATION, MessageRole.AGENT, "agent text"),
                Message.from(CONVERSATION, MessageRole.SYSTEM, "system text"));

        LlmInvocationContext context = builder.build(messages, MODEL, SYSTEM_PROMPT);

        assertThat(context.messages()).containsExactly(
                new LlmMessage(LlmRole.USER, "user text"),
                new LlmMessage(LlmRole.ASSISTANT, "agent text"),
                new LlmMessage(LlmRole.SYSTEM, "system text"));
    }

    @Test
    void build_whenUnknownModel_usesConservativeFallbackWindow() {
        // An unregistered model no longer fails: ModelContextWindow returns a conservative
        // fallback window so the invocation degrades (history trimmed) rather than breaking.
        List<Message> messages = List.of(Message.from(CONVERSATION, MessageRole.USER, "hi"));

        LlmInvocationContext context = builder.build(messages, "gpt-4", SYSTEM_PROMPT);

        assertThat(context.messages()).hasSize(1);
        assertThat(context.contextWindowLimit()).isEqualTo(16_384);
        assertThat(context.reservedForResponse()).isEqualTo(10_000);
    }

    @Test
    void build_whenEmptyConversation_returnsEmptyContextWithoutThrowing() {
        LlmInvocationContext context = builder.build(List.of(), MODEL, SYSTEM_PROMPT);

        assertThat(context.messages()).isEmpty();
        assertThat(context.estimatedTokens()).isZero();
        assertThat(context.contextWindowLimit()).isEqualTo(200_000);
        assertThat(context.reservedForResponse()).isEqualTo(10_000);
    }

    @Test
    void build_whenSystemPromptIsLarge_doesNotCountItAgainstWindow() {
        // Messages alone = 150_000 tokens (under 190_000). System prompt = 50_000 tokens.
        // Jointly they would exceed the window, but the prompt must not be counted, so every
        // message is retained.
        List<Message> messages = new ArrayList<>();
        for (int i = 0; i < 75; i++) {
            messages.add(userMessageOfLength("M" + i, 7000)); // 2000 tokens each => 150_000
        }
        String largeSystemPrompt = "s".repeat(175_000); // ceil(175000/3.5) = 50_000 tokens

        LlmInvocationContext context = builder.build(messages, MODEL, largeSystemPrompt);

        assertThat(context.messages()).hasSize(75);
        assertThat(context.estimatedTokens()).isEqualTo(150_000);
        assertThat(context.estimatedTokens()).isLessThanOrEqualTo(EFFECTIVE_WINDOW);
        assertThat(context.systemPrompt()).isEqualTo(largeSystemPrompt);
    }
}
