package dev.cauce.orchestration.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.cauce.core.message.Message;
import dev.cauce.core.message.MessageRole;
import dev.cauce.core.tool.ToolCall;
import dev.cauce.core.tool.ToolDefinition;
import dev.cauce.core.tool.ToolResult;
import dev.cauce.llm.model.LlmMessage;
import dev.cauce.llm.model.LlmRole;
import dev.cauce.orchestration.exception.MessageTooLargeForContextException;
import dev.cauce.orchestration.exception.PromptDoesNotFitContextException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ContextBuilderTest {

    private static final String MODEL = "claude-sonnet-4-7"; // 200_000 window
    private static final int EFFECTIVE_WINDOW = 200_000 - ContextBuilder.RESERVED_FOR_RESPONSE; // 190_000
    /** Unknown to ModelContextWindow: the 16_384 fallback, so 6_384 tokens of effective window. */
    private static final String SMALL_MODEL = "some-unknown-model";
    private static final String SYSTEM_PROMPT = "You are a helpful assistant.";
    /** No prompt, for the tests whose arithmetic is the history's alone. */
    private static final String NO_PROMPT = "";
    private static final UUID CONVERSATION = UUID.randomUUID();

    private final ContextBuilder builder = new ContextBuilder();

    /** A USER message whose content has exactly {@code chars} characters, tagged for identity. */
    private static Message userMessageOfLength(String tag, int chars) {
        String prefix = tag + ":";
        String content = prefix + "x".repeat(chars - prefix.length());
        return Message.from(CONVERSATION, MessageRole.USER, content);
    }

    @Test
    void build_inAServedWindowOf4096_reservesHalfAndKeepsHistoryInTheOtherHalf() {
        // 12 messages of 700 chars => 200 tokens each; 2048 tokens of history fit 10 of them.
        List<Message> messages = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            messages.add(userMessageOfLength("m" + i, 700));
        }

        LlmInvocationContext context = builder.build(messages, "qwen2.5:3b", 4096, SYSTEM_PROMPT, List.of(), Set.of());

        assertThat(context.contextWindowLimit()).isEqualTo(4096);
        assertThat(context.reservedForResponse()).isEqualTo(2048);
        assertThat(context.messages()).hasSize(10);
        assertThat(context.estimatedTokens() + context.fixedTokens() + context.reservedForResponse())
                .isLessThanOrEqualTo(4096);
    }

    @Test
    void build_countsThePromptAndTheToolSchemasAgainstTheWindow() {
        ToolDefinition tool = new ToolDefinition("search_catalog", "Busca en la carta.",
                Map.of("type", "object", "properties", Map.of("query", Map.of("type", "string"))));
        // 2048 tokens of history window; 12 messages of 200 tokens each.
        List<Message> messages = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            messages.add(userMessageOfLength("m" + i, 700));
        }
        String prompt = "p".repeat(1400); // 400 tokens

        LlmInvocationContext context = builder.build(messages, "qwen2.5:3b", 4096, prompt, List.of(tool), Set.of());

        int fixed = 400 + ContextBuilder.TOOL_USE_OVERHEAD + ContextBuilder.PER_TOOL_OVERHEAD
                + TokenEstimator.estimate("search_catalog") + TokenEstimator.estimate("Busca en la carta.")
                + TokenEstimator.estimateJson(tool.inputSchema());
        assertThat(context.fixedTokens()).isEqualTo(fixed);
        assertThat(context.messages()).hasSize((2048 - fixed) / 200);
        assertThat(context.estimatedTokens() + context.fixedTokens() + context.reservedForResponse())
                .isLessThanOrEqualTo(4096);
    }

    @Test
    void build_whenThePromptAloneFillsTheWindow_failsAsThePromptNotFitting() {
        String prompt = "p".repeat(7168); // 2048 tokens: the whole history window of 4096

        assertThatThrownBy(() -> builder.build(List.of(userMessageOfLength("hi", 10)), "qwen2.5:3b", 4096,
                prompt, List.of(), Set.of()))
                .isInstanceOf(PromptDoesNotFitContextException.class)
                .hasMessageStartingWith("The system prompt and tools do not fit the context window of 4096 tokens")
                .hasMessageContaining("2048");
    }

    @Test
    void build_whenThePromptAndTheCurrentMessageTogetherOverflow_failsAsThePromptNotFitting() {
        String prompt = "p".repeat(5250); // 1500 tokens
        Message current = userMessageOfLength("ASK", 2800); // 800 tokens: fits 2048 alone, not with the prompt

        assertThatThrownBy(() -> builder.build(List.of(current), "qwen2.5:3b", 4096, prompt, List.of(), Set.of()))
                .isInstanceOf(PromptDoesNotFitContextException.class)
                .hasMessageContaining("1500 tokens (0 tools)")
                .hasMessageContaining("current message at 800");
    }

    @Test
    void build_whenTheCurrentMessageIsTooLargeEvenWithoutAPrompt_isTheMessageNotTheAgent() {
        Message current = userMessageOfLength("HUGE", 7700); // 2200 tokens > 2048

        assertThatThrownBy(() -> builder.build(List.of(current), "qwen2.5:3b", 4096, SYSTEM_PROMPT, List.of(),
                Set.of()))
                .isInstanceOf(MessageTooLargeForContextException.class);
    }

    @Test
    void responseTokens_inASmallWindow_isCappedToWhatWasHeldBack() {
        LlmInvocationContext context = builder.build(
                List.of(userMessageOfLength("hi", 10)), "qwen2.5:3b", 4096, SYSTEM_PROMPT, List.of(), Set.of());

        assertThat(context.responseTokens(4096)).isEqualTo(2048);
        assertThat(context.responseTokens(1000)).isEqualTo(1000);
    }

    @Test
    void responseTokens_inALargeWindow_isTheAgentsOwn() {
        LlmInvocationContext context = builder.build(List.of(userMessageOfLength("hi", 10)), MODEL, SYSTEM_PROMPT);

        assertThat(context.reservedForResponse()).isEqualTo(ContextBuilder.RESERVED_FOR_RESPONSE);
        assertThat(context.responseTokens(16_000)).isEqualTo(16_000);
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

        LlmInvocationContext context = builder.build(messages, MODEL, NO_PROMPT);

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

        assertThatThrownBy(() -> builder.build(List.of(huge), MODEL, NO_PROMPT))
                .isInstanceOf(MessageTooLargeForContextException.class)
                .hasMessageContaining("200000")
                .hasMessageContaining(String.valueOf(EFFECTIVE_WINDOW));
    }

    @Test
    void build_whenMostRecentMessageExactlyFillsWindow_isIncluded() {
        // 665_000 chars => ceil(665000/3.5) = 190_000 tokens == effective window (boundary).
        Message exact = userMessageOfLength("EXACT", 665_000);

        LlmInvocationContext context = builder.build(List.of(exact), MODEL, NO_PROMPT);

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
    void build_translatesToolCallAndToolResultMessages() {
        ToolCall call = new ToolCall("call-1", "get_current_time", Map.of("tz", "UTC"));
        ToolResult result = ToolResult.success("call-1", "get_current_time", "2026-06-13T10:15:30Z");
        List<Message> messages = List.of(
                Message.from(CONVERSATION, MessageRole.USER, "What time is it?"),
                Message.toolCall(CONVERSATION, call),
                Message.toolResult(CONVERSATION, result),
                Message.from(CONVERSATION, MessageRole.AGENT, "It's 10:15 UTC."));

        LlmInvocationContext context = builder.build(messages, MODEL, SYSTEM_PROMPT);

        assertThat(context.messages()).containsExactly(
                new LlmMessage(LlmRole.USER, "What time is it?"),
                LlmMessage.toolCall(call),
                LlmMessage.toolResult(result),
                new LlmMessage(LlmRole.ASSISTANT, "It's 10:15 UTC."));
    }

    @Test
    void build_toolCallMessage_countsInputArgumentTokens() {
        ToolCall call = new ToolCall("call-1", "search", Map.of("query", "x".repeat(70)));
        Message user = Message.from(CONVERSATION, MessageRole.USER, "Search for it");
        Message toolCallMessage = Message.toolCall(CONVERSATION, call);

        LlmInvocationContext context = builder.build(List.of(user, toolCallMessage), MODEL, SYSTEM_PROMPT);

        int expected = TokenEstimator.estimate(user.content())
                + TokenEstimator.estimate(toolCallMessage.content())
                + TokenEstimator.estimate(String.valueOf(call.input()));
        assertThat(context.estimatedTokens()).isEqualTo(expected);
    }

    // --- ADR 0005, C2: the window always starts at a user turn ---

    @Test
    void build_whenTheCutOrphansAToolResult_startsTheWindowAtTheNextUserTurn() {
        // 6000 tokens of window. The old turn's call does not fit, its result would; the window
        // must not open on that orphaned result.
        ToolCall call = new ToolCall("call-1", "search", Map.of("query", "x".repeat(70_000)));
        List<Message> messages = List.of(
                userMessageOfLength("OLD", 7000),
                Message.toolCall(CONVERSATION, call),
                Message.toolResult(CONVERSATION, ToolResult.success("call-1", "search", "found")),
                Message.from(CONVERSATION, MessageRole.AGENT, "Found it."),
                userMessageOfLength("NEW", 7000));

        LlmInvocationContext context = builder.build(messages, SMALL_MODEL, SYSTEM_PROMPT);

        assertThat(context.messages()).hasSize(1);
        assertThat(context.messages().get(0).role()).isEqualTo(LlmRole.USER);
        assertThat(context.messages().get(0).content()).startsWith("NEW:");
        assertThat(context.estimatedTokens()).isEqualTo(2000);
    }

    @Test
    void build_whenTheCutLandsOnAnAssistantTurn_dropsItAndKeepsWholeToolTurns() {
        ToolCall call = new ToolCall("call-2", "get_current_time", Map.of());
        ToolResult result = ToolResult.success("call-2", "get_current_time", "10:15");
        List<Message> messages = List.of(
                userMessageOfLength("OLD", 7000),
                Message.from(CONVERSATION, MessageRole.AGENT, "a".repeat(10_500)), // 3000 tokens: fits, OLD then does not
                userMessageOfLength("NEW", 7000),
                Message.toolCall(CONVERSATION, call),
                Message.toolResult(CONVERSATION, result));

        LlmInvocationContext context = builder.build(messages, SMALL_MODEL, SYSTEM_PROMPT);

        assertThat(context.messages()).extracting(LlmMessage::role)
                .containsExactly(LlmRole.USER, LlmRole.ASSISTANT, LlmRole.USER);
        assertThat(context.messages().get(0).content()).startsWith("NEW:");
        assertThat(context.messages()).contains(LlmMessage.toolCall(call), LlmMessage.toolResult(result));
    }

    @Test
    void build_whenNoUserTurnFits_throwsInsteadOfSendingAHeadlessHistory() {
        // The current turn's user message plus its tool round exceed the window: only the
        // tool messages would fit, and a history that opens on them is malformed.
        ToolCall call = new ToolCall("call-3", "search", Map.of("query", "q"));
        List<Message> messages = List.of(
                userMessageOfLength("ASK", 7000),
                Message.toolCall(CONVERSATION, call),
                Message.toolResult(CONVERSATION, ToolResult.success("call-3", "search", "r".repeat(17_000))));

        assertThatThrownBy(() -> builder.build(messages, SMALL_MODEL, SYSTEM_PROMPT))
                .isInstanceOf(MessageTooLargeForContextException.class)
                .hasMessageContaining("current turn");
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
    void build_whenSystemPromptIsLarge_countsItAgainstTheWindow() {
        // Messages alone = 150_000 tokens (under 190_000). System prompt = 50_000 tokens: what
        // is left for the history is 140_000, so the newest 70 messages are retained.
        List<Message> messages = new ArrayList<>();
        for (int i = 0; i < 75; i++) {
            messages.add(userMessageOfLength("M" + i, 7000)); // 2000 tokens each => 150_000
        }
        String largeSystemPrompt = "s".repeat(175_000); // ceil(175000/3.5) = 50_000 tokens

        LlmInvocationContext context = builder.build(messages, MODEL, largeSystemPrompt);

        assertThat(context.messages()).hasSize(70);
        assertThat(context.messages().get(0).content()).startsWith("M5:");
        assertThat(context.estimatedTokens()).isEqualTo(140_000);
        assertThat(context.fixedTokens()).isEqualTo(50_000);
        assertThat(context.systemPrompt()).isEqualTo(largeSystemPrompt);
    }

    @Test
    void build_neverSendsFallbackMessagesToTheModel() {
        List<Message> messages = List.of(
                Message.from(CONVERSATION, MessageRole.USER, "Hello"),
                Message.fallback(CONVERSATION, "Sorry, try again later.", "LLM_ERROR"),
                Message.from(CONVERSATION, MessageRole.USER, "Hello again"));

        LlmInvocationContext context = builder.build(messages, MODEL, SYSTEM_PROMPT);

        assertThat(context.messages()).extracting(LlmMessage::content)
                .containsExactly("Hello", "Hello again");
        assertThat(context.estimatedTokens())
                .isEqualTo(TokenEstimator.estimate("Hello") + TokenEstimator.estimate("Hello again"));
    }

    @Test
    void build_elidingPastLookups_keepsEveryCallAndPair_butOnlyEarlierTurnsLookupResultsLoseTheirContent() {
        String menu = "{\"items\":[" + "{\"name\":\"Patatas bravas\",\"price\":\"5.50\",\"alergenos\":[]},".repeat(8)
                + "{\"name\":\"Flan de huevo\",\"price\":\"4.50\"}]}";
        List<Message> messages = List.of(
                Message.from(CONVERSATION, MessageRole.USER, "¿Qué tenéis de tapas?"),
                Message.toolCall(CONVERSATION, new ToolCall("c1", "search_catalog", Map.of("query", "tapas"))),
                Message.toolResult(CONVERSATION, ToolResult.success("c1", "search_catalog", menu)),
                Message.toolCall(CONVERSATION, new ToolCall("c2", "propose_booking", Map.of())),
                Message.toolResult(CONVERSATION, ToolResult.success("c2", "propose_booking", "{\"code\":\"DCK66M\"}")),
                Message.from(CONVERSATION, MessageRole.AGENT, "Bravas a 5,50 €. ¿La confirmo?"),
                Message.from(CONVERSATION, MessageRole.USER, "Sí, ¿y las bravas pican?"),
                Message.toolCall(CONVERSATION, new ToolCall("c3", "search_catalog", Map.of("query", "bravas"))),
                Message.toolResult(CONVERSATION, ToolResult.success("c3", "search_catalog", menu)));

        LlmInvocationContext elided = builder.build(messages, MODEL, SYSTEM_PROMPT, java.util.Set.of("search_catalog"));
        LlmInvocationContext full = builder.build(messages, MODEL, SYSTEM_PROMPT);

        List<String> results = elided.messages().stream().filter(m -> m.toolContent() instanceof ToolResult)
                .map(m -> ((ToolResult) m.toolContent()).output()).toList();
        // The earlier turn's menu goes; its action (the booking code) and the current turn's menu stay.
        assertThat(results).containsExactly(ContextBuilder.ELIDED_RESULT, "{\"code\":\"DCK66M\"}", menu);
        assertThat(elided.messages()).hasSize(messages.size());
        assertThat(elided.messages()).filteredOn(m -> m.toolContent() instanceof ToolCall).hasSize(3);
        assertThat(elided.estimatedTokens()).isLessThan(full.estimatedTokens());
        assertThat(full.messages().stream().filter(m -> m.toolContent() instanceof ToolResult)
                .map(m -> ((ToolResult) m.toolContent()).output())).doesNotContain(ContextBuilder.ELIDED_RESULT);
    }
}
