package dev.cauce.orchestration.context;

import dev.cauce.core.agent.Agent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.cauce.core.message.Message;
import dev.cauce.core.message.MessagePart;
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
    /** The reservation of an agent that asks for the default response tokens: 4096 + 2000. */
    private static final int DEFAULT_RESERVATION = Agent.DEFAULT_MAX_RESPONSE_TOKENS + ContextBuilder.RESPONSE_BUFFER;
    /** What the window arithmetic below was written for: an agent asking for 8000 reserves 10_000. */
    private static final int ASKS_8000 = 8_000;
    private static final int EFFECTIVE_WINDOW = 200_000 - (ASKS_8000 + ContextBuilder.RESPONSE_BUFFER); // 190_000
    /** Unknown to ModelContextWindow: the 16_384 fallback, so 6_384 tokens of effective window for {@link #ASKS_8000}. */
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

    /** {@code builder.build} for an agent that asks for {@link #ASKS_8000} response tokens. */
    private LlmInvocationContext asks8000(List<Message> messages, String model, String systemPrompt) {
        return builder.build(messages, model, ModelContextWindow.contextWindowFor(model), systemPrompt, List.of(),
                Set.of(), null, ASKS_8000);
    }

    @Test
    void build_reservesTheAgentsResponseTokensPlusTheBuffer_notAFlatAmount() {
        List<Message> hi = List.of(userMessageOfLength("hi", 10));

        assertThat(builder.build(hi, MODEL, 200_000, SYSTEM_PROMPT, List.of(), Set.of(), null, 4096)
                .reservedForResponse()).isEqualTo(6_096);
        assertThat(builder.build(hi, MODEL, 200_000, SYSTEM_PROMPT, List.of(), Set.of(), null, 16_000)
                .reservedForResponse()).isEqualTo(18_000);
        assertThat(builder.build(hi, MODEL, 200_000, SYSTEM_PROMPT, List.of(), Set.of(), null, 16_000)
                .responseTokens(16_000)).isEqualTo(16_000);
    }

    @Test
    void build_anAgentOf6500TokensOfPromptAndTools_fitsA16KWindow_asking4096() {
        // The bar's customer agent with twelve tools, 2026-11-09: 6473 estimated tokens. With the flat
        // 10_000 it did not fit 16_384; with 4096 + 2000 it leaves room for its history.
        String prompt = "p".repeat(16_180); // 6472 tokens
        LlmInvocationContext context = builder.build(List.of(userMessageOfLength("hola", 10)), "qwen2.5:7b", 16_384,
                prompt, List.of(), Set.of(), null, Agent.DEFAULT_MAX_RESPONSE_TOKENS);

        assertThat(context.reservedForResponse()).isEqualTo(6_096);
        assertThat(context.fixedTokens() + context.estimatedTokens() + context.reservedForResponse())
                .isLessThanOrEqualTo(16_384);
    }

    @Test
    void build_inAServedWindowOf4096_reservesHalfAndKeepsHistoryInTheOtherHalf() {
        // 12 messages of 500 chars => 200 tokens each; 2048 tokens of history fit 10 of them.
        List<Message> messages = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            messages.add(userMessageOfLength("m" + i, 500));
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
            messages.add(userMessageOfLength("m" + i, 500));
        }
        String prompt = "p".repeat(1000); // 400 tokens

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
        String prompt = "p".repeat(5120); // 2048 tokens: the whole history window of 4096

        assertThatThrownBy(() -> builder.build(List.of(userMessageOfLength("hi", 10)), "qwen2.5:3b", 4096,
                prompt, List.of(), Set.of()))
                .isInstanceOf(PromptDoesNotFitContextException.class)
                .hasMessageStartingWith("The system prompt and tools do not fit the context window of 4096 tokens")
                .hasMessageContaining("2048");
    }

    @Test
    void build_whenThePromptAndTheCurrentMessageTogetherOverflow_failsAsThePromptNotFitting() {
        String prompt = "p".repeat(3750); // 1500 tokens
        Message current = userMessageOfLength("ASK", 2000); // 800 tokens: fits 2048 alone, not with the prompt

        assertThatThrownBy(() -> builder.build(List.of(current), "qwen2.5:3b", 4096, prompt, List.of(), Set.of()))
                .isInstanceOf(PromptDoesNotFitContextException.class)
                .hasMessageContaining("1500 tokens (0 tools)")
                .hasMessageContaining("current message at 800");
    }

    @Test
    void build_whenTheCurrentMessageIsTooLargeEvenWithoutAPrompt_isTheMessageNotTheAgent() {
        Message current = userMessageOfLength("HUGE", 5500); // 2200 tokens > 2048

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
        LlmInvocationContext context = builder.build(List.of(userMessageOfLength("hi", 10)), MODEL, 200_000,
                SYSTEM_PROMPT, List.of(), Set.of(), null, 16_000);

        assertThat(context.reservedForResponse()).isEqualTo(16_000 + ContextBuilder.RESPONSE_BUFFER);
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
        assertThat(context.reservedForResponse()).isEqualTo(DEFAULT_RESERVATION);
        assertThat(context.systemPrompt()).isEqualTo(SYSTEM_PROMPT);
    }

    @Test
    void build_whenConversationExceedsWindow_keepsMostRecentSliceChronological() {
        // 100 messages of 5000 chars each => 2000 tokens each. 190_000 / 2000 = 95 fit exactly.
        List<Message> messages = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            messages.add(userMessageOfLength("MSG" + i, 5000));
        }

        LlmInvocationContext context = asks8000(messages, MODEL, NO_PROMPT);

        assertThat(context.messages()).hasSize(95);
        assertThat(context.estimatedTokens()).isEqualTo(EFFECTIVE_WINDOW); // 190_000
        assertThat(context.estimatedTokens()).isLessThanOrEqualTo(EFFECTIVE_WINDOW);
        // The retained slice is the newest 95 (indices 5..99), in chronological order.
        assertThat(context.messages().get(0).content()).startsWith("MSG5:");
        assertThat(context.messages().get(94).content()).startsWith("MSG99:");
    }

    @Test
    void build_whenMostRecentMessageAloneExceedsWindow_throwsMessageTooLarge() {
        // 500_000 chars => ceil(500000/2.5) = 200_000 tokens > 190_000 effective window.
        Message huge = userMessageOfLength("HUGE", 500_000);

        assertThatThrownBy(() -> asks8000(List.of(huge), MODEL, NO_PROMPT))
                .isInstanceOf(MessageTooLargeForContextException.class)
                .hasMessageContaining("200000")
                .hasMessageContaining(String.valueOf(EFFECTIVE_WINDOW));
    }

    @Test
    void build_whenMostRecentMessageExactlyFillsWindow_isIncluded() {
        // 475_000 chars => ceil(475000/2.5) = 190_000 tokens == effective window (boundary).
        Message exact = userMessageOfLength("EXACT", 475_000);

        LlmInvocationContext context = asks8000(List.of(exact), MODEL, NO_PROMPT);

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
        ToolCall call = new ToolCall("call-1", "search", Map.of("query", "x".repeat(50_000)));
        List<Message> messages = List.of(
                userMessageOfLength("OLD", 5000),
                Message.toolCall(CONVERSATION, call),
                Message.toolResult(CONVERSATION, ToolResult.success("call-1", "search", "found")),
                Message.from(CONVERSATION, MessageRole.AGENT, "Found it."),
                userMessageOfLength("NEW", 5000));

        LlmInvocationContext context = asks8000(messages, SMALL_MODEL, SYSTEM_PROMPT);

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
                userMessageOfLength("OLD", 5000),
                Message.from(CONVERSATION, MessageRole.AGENT, "a".repeat(7_500)), // 3000 tokens: fits, OLD then does not
                userMessageOfLength("NEW", 5000),
                Message.toolCall(CONVERSATION, call),
                Message.toolResult(CONVERSATION, result));

        LlmInvocationContext context = asks8000(messages, SMALL_MODEL, SYSTEM_PROMPT);

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
                userMessageOfLength("ASK", 5000),
                Message.toolCall(CONVERSATION, call),
                Message.toolResult(CONVERSATION, ToolResult.success("call-3", "search", "r".repeat(12_145))));

        assertThatThrownBy(() -> asks8000(messages, SMALL_MODEL, SYSTEM_PROMPT))
                .isInstanceOf(MessageTooLargeForContextException.class)
                .hasMessageContaining("current turn");
    }

    @Test
    void build_aVoiceNote_readsAsItsTranscriptMarkedTranscribed_andCountsItsTokens() {
        Message note = Message.fromParts(CONVERSATION, MessageRole.USER, List.of(
                dev.cauce.core.message.MessagePart.Audio.received("audio/ogg", "r", null, 4)
                        .transcribed("Mesa para cuatro", "a".repeat(64), 10, 4)));

        LlmInvocationContext context = builder.build(List.of(note), MODEL, SYSTEM_PROMPT);

        assertThat(context.messages()).extracting(LlmMessage::content)
                .containsExactly("[Transcribed voice note] Mesa para cuatro");
        assertThat(context.estimatedTokens())
                .isEqualTo(TokenEstimator.estimate("[Transcribed voice note] Mesa para cuatro"));
        assertThat(note.content()).as("only the model reads the mark").isEqualTo("Mesa para cuatro");
    }

    @Test
    void build_withTheBusinessTimeZone_readsEachUserMessageWithWhenItWasSent_theSameOnEveryTurn() {
        java.time.ZoneId madrid = java.time.ZoneId.of("Europe/Madrid");
        // Two days apart, as in a thread that stays open: the newest stamp is "now".
        Message thursday = Message.from(CONVERSATION, MessageRole.USER, "Hola")
                .stampedAt(java.time.Instant.parse("2026-10-22T08:01:23Z"));
        Message reply = Message.from(CONVERSATION, MessageRole.AGENT, "Buenas")
                .stampedAt(java.time.Instant.parse("2026-10-22T08:01:25Z"));
        Message note = Message.fromParts(CONVERSATION, MessageRole.USER, List.of(
                dev.cauce.core.message.MessagePart.Audio.received("audio/ogg", "r", null, 4)
                        .transcribed("Mesa para dos mañana", "a".repeat(64), 10, 4)))
                .stampedAt(java.time.Instant.parse("2026-10-24T22:30:00Z")); // 00:30 on Sunday in Madrid

        LlmInvocationContext first = builder.build(List.of(thursday, reply), MODEL, 200_000, SYSTEM_PROMPT,
                List.of(), Set.of(), madrid);
        LlmInvocationContext later = builder.build(List.of(thursday, reply, note), MODEL, 200_000, SYSTEM_PROMPT,
                List.of(), Set.of(), madrid);

        assertThat(later.messages()).extracting(LlmMessage::content).containsExactly(
                "[Sent Thursday 2026-10-22 10:01 Europe/Madrid] Hola",
                "Buenas",
                "[Sent Sunday 2026-10-25 00:30 Europe/Madrid] [Transcribed voice note] Mesa para dos mañana");
        assertThat(later.messages().get(0)).as("a message reads the same on every later turn: the cache holds")
                .isEqualTo(first.messages().get(0));
        assertThat(later.estimatedTokens()).isEqualTo(TokenEstimator.estimate(later.messages().get(0).content())
                + TokenEstimator.estimate("Buenas") + TokenEstimator.estimate(later.messages().get(2).content()));
        assertThat(builder.build(List.of(thursday), MODEL, SYSTEM_PROMPT).messages())
                .as("without a time zone, no stamp").extracting(LlmMessage::content).containsExactly("Hola");
    }

    @Test
    void build_whenUnknownModel_usesConservativeFallbackWindow() {
        // An unregistered model no longer fails: ModelContextWindow returns a conservative
        // fallback window so the invocation degrades (history trimmed) rather than breaking.
        List<Message> messages = List.of(Message.from(CONVERSATION, MessageRole.USER, "hi"));

        LlmInvocationContext context = builder.build(messages, "gpt-4", SYSTEM_PROMPT);

        assertThat(context.messages()).hasSize(1);
        assertThat(context.contextWindowLimit()).isEqualTo(16_384);
        assertThat(context.reservedForResponse()).isEqualTo(DEFAULT_RESERVATION);
    }

    @Test
    void build_whenEmptyConversation_returnsEmptyContextWithoutThrowing() {
        LlmInvocationContext context = builder.build(List.of(), MODEL, SYSTEM_PROMPT);

        assertThat(context.messages()).isEmpty();
        assertThat(context.estimatedTokens()).isZero();
        assertThat(context.contextWindowLimit()).isEqualTo(200_000);
        assertThat(context.reservedForResponse()).isEqualTo(DEFAULT_RESERVATION);
    }

    @Test
    void build_whenSystemPromptIsLarge_countsItAgainstTheWindow() {
        // Messages alone = 150_000 tokens (under 190_000). System prompt = 50_000 tokens: what
        // is left for the history is 140_000, so the newest 70 messages are retained.
        List<Message> messages = new ArrayList<>();
        for (int i = 0; i < 75; i++) {
            messages.add(userMessageOfLength("M" + i, 5000)); // 2000 tokens each => 150_000
        }
        String largeSystemPrompt = "s".repeat(125_000); // ceil(125000/2.5) = 50_000 tokens

        LlmInvocationContext context = asks8000(messages, MODEL, largeSystemPrompt);

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

    @Test
    void aPageContext_readsAsLabelledDataAfterThePersonsWords_neverAsTheirWords() {
        Message message = Message.fromParts(UUID.randomUUID(), MessageRole.USER, List.of(
                new MessagePart.Text("¿Qué incluye?"),
                new MessagePart.PageContext("/pagar", "plan-plus", List.of(new MessagePart.PageContext.CartLine("plan-plus", 1)),
                        "¿Te ayudo a terminar?")));

        assertThat(ContextBuilder.modelText(message)).isEqualTo("¿Qué incluye?\n" + ContextBuilder.PAGE_CONTEXT
                + "path /pagar; viewing plan-plus; cart plan-plus x1; the chat opened by itself and you greeted: "
                + "\"¿Te ayudo a terminar?\"]");
        assertThat(ContextBuilder.pageContextLine(new MessagePart.PageContext("/", null, List.of(), null)))
                .isEqualTo(ContextBuilder.PAGE_CONTEXT + "path /]");
    }
}
