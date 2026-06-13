package dev.cauce.orchestration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.cauce.core.agent.Agent;
import dev.cauce.core.conversation.ConversationNotFoundException;
import dev.cauce.core.message.Message;
import dev.cauce.core.message.MessageRole;
import dev.cauce.core.tool.ToolCall;
import dev.cauce.core.tool.ToolDefinition;
import dev.cauce.core.tool.ToolResult;
import dev.cauce.llm.exception.LlmRateLimitException;
import dev.cauce.llm.model.FinishReason;
import dev.cauce.llm.model.LlmInvocation;
import dev.cauce.llm.model.LlmMessage;
import dev.cauce.llm.model.LlmResponse;
import dev.cauce.llm.model.LlmUsage;
import dev.cauce.llm.spi.LlmProvider;
import dev.cauce.llm.spi.LlmProviderRegistry;
import dev.cauce.orchestration.context.ContextBuilder;
import dev.cauce.orchestration.exception.LlmProviderNotAvailableException;
import dev.cauce.orchestration.exception.MaxToolIterationsExceededException;
import dev.cauce.orchestration.service.ConversationGateway.LoadedConversation;
import dev.cauce.tools.clock.ClockTool;
import dev.cauce.tools.spi.Tool;
import dev.cauce.tools.spi.ToolRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.mock.env.MockEnvironment;

class OrchestratorServiceTest {

    private static final String MODEL = "claude-sonnet-4-7";
    private static final String PROVIDER = "anthropic";
    private static final Instant FIXED = Instant.parse("2026-06-13T10:15:30Z");

    private final UUID conversationId = UUID.randomUUID();
    private final UUID tenantId = UUID.randomUUID();
    private final UUID triggerId = UUID.randomUUID();

    private ConversationGateway gateway;
    private LlmProviderRegistry registry;
    private OrchestrationErrorRecorder errorRecorder;
    private LlmProvider provider;

    @BeforeEach
    void setUp() {
        gateway = Mockito.mock(ConversationGateway.class);
        registry = Mockito.mock(LlmProviderRegistry.class);
        errorRecorder = Mockito.mock(OrchestrationErrorRecorder.class);
        provider = Mockito.mock(LlmProvider.class);
    }

    @Test
    void respondToMessage_noToolsAvailable_persistsAgentReplyAndOffersNoTools() {
        stubLoadAndEchoAppend();
        when(registry.getProvider(PROVIDER)).thenReturn(Optional.of(provider));
        when(provider.invoke(any())).thenReturn(reply("Hola, soy un agente"));

        Message result = serviceWith(emptyRegistry()).respondToMessage(conversationId, triggerId);

        assertThat(result.role()).isEqualTo(MessageRole.AGENT);
        assertThat(result.content()).isEqualTo("Hola, soy un agente");
        ArgumentCaptor<LlmInvocation> captor = ArgumentCaptor.forClass(LlmInvocation.class);
        verify(provider).invoke(captor.capture());
        // Empty registry => no tools offered: the request is byte-identical to the single-shot path.
        assertThat(captor.getValue().tools()).isEmpty();
        assertThat(captor.getValue().messages()).extracting(LlmMessage::content).containsExactly("Hola");
        verifyNoInteractions(errorRecorder);
    }

    @Test
    void respondToMessage_buildsInvocationFromAgentConfigAndContext() {
        stubLoadAndEchoAppend();
        when(registry.getProvider(PROVIDER)).thenReturn(Optional.of(provider));
        when(provider.invoke(any())).thenReturn(reply("ok"));

        serviceWith(emptyRegistry()).respondToMessage(conversationId, triggerId);

        ArgumentCaptor<LlmInvocation> captor = ArgumentCaptor.forClass(LlmInvocation.class);
        verify(provider).invoke(captor.capture());
        LlmInvocation invocation = captor.getValue();
        assertThat(invocation.modelName()).isEqualTo(MODEL);
        assertThat(invocation.systemPrompt()).isEqualTo("You are helpful.");
        assertThat(invocation.temperature()).isEqualTo(0.7);
        assertThat(invocation.maxTokens()).isEqualTo(4096);
    }

    @Test
    void respondToMessage_whenModelRequestsTool_executesItFeedsResultBackAndReplies() {
        stubLoadAndEchoAppend();
        when(registry.getProvider(PROVIDER)).thenReturn(Optional.of(provider));
        when(provider.invoke(any())).thenReturn(
                toolRequest("get_current_time"),
                reply("It is 2026-06-13T10:15:30Z."));

        Message result = serviceWith(clockRegistry()).respondToMessage(conversationId, triggerId);

        assertThat(result.role()).isEqualTo(MessageRole.AGENT);
        assertThat(result.content()).isEqualTo("It is 2026-06-13T10:15:30Z.");

        // Persisted in order: TOOL_CALL, TOOL_RESULT (the clock output), then the final AGENT reply.
        List<Message> persisted = capturedAppends(3);
        assertThat(persisted.get(0).role()).isEqualTo(MessageRole.TOOL_CALL);
        assertThat(persisted.get(1).role()).isEqualTo(MessageRole.TOOL_RESULT);
        ToolResult result1 = (ToolResult) persisted.get(1).toolContent().orElseThrow();
        assertThat(result1.output()).isEqualTo("2026-06-13T10:15:30Z");
        assertThat(result1.isError()).isFalse();
        assertThat(persisted.get(2).role()).isEqualTo(MessageRole.AGENT);
        verify(provider, times(2)).invoke(any());
        verifyNoInteractions(errorRecorder);
    }

    @Test
    void respondToMessage_whenToolThrows_feedsErrorBackAndCompletes() {
        stubLoadAndEchoAppend();
        when(registry.getProvider(PROVIDER)).thenReturn(Optional.of(provider));
        when(provider.invoke(any())).thenReturn(toolRequest("boom_tool"), reply("Recovered."));

        Message result = serviceWith(new ToolRegistry(List.of(throwingTool("boom_tool"))))
                .respondToMessage(conversationId, triggerId);

        assertThat(result.content()).isEqualTo("Recovered."); // invocation completes, not failed
        ToolResult toolResult = (ToolResult) capturedAppends(3).get(1).toolContent().orElseThrow();
        assertThat(toolResult.isError()).isTrue();
        assertThat(toolResult.output()).contains("Tool execution failed");
        verifyNoInteractions(errorRecorder);
    }

    @Test
    void respondToMessage_whenToolUnknown_feedsErrorBackAndCompletes() {
        stubLoadAndEchoAppend();
        when(registry.getProvider(PROVIDER)).thenReturn(Optional.of(provider));
        when(provider.invoke(any())).thenReturn(toolRequest("does_not_exist"), reply("Done."));

        Message result = serviceWith(emptyRegistry()).respondToMessage(conversationId, triggerId);

        assertThat(result.content()).isEqualTo("Done.");
        ToolResult toolResult = (ToolResult) capturedAppends(3).get(1).toolContent().orElseThrow();
        assertThat(toolResult.isError()).isTrue();
        assertThat(toolResult.output()).contains("Unknown tool");
        verifyNoInteractions(errorRecorder);
    }

    @Test
    void respondToMessage_whenModelNeverStopsCallingTools_failsAtTheCap() {
        stubLoadAndEchoAppend();
        when(registry.getProvider(PROVIDER)).thenReturn(Optional.of(provider));
        when(provider.invoke(any())).thenReturn(toolRequest("get_current_time"));

        assertThatThrownBy(() ->
                serviceWith(clockRegistry()).respondToMessage(conversationId, triggerId))
                .isInstanceOf(MaxToolIterationsExceededException.class);

        verify(provider, times(OrchestratorService.MAX_TOOL_ITERATIONS)).invoke(any());
        ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
        verify(errorRecorder).recordError(eq(conversationId), error.capture());
        assertThat(error.getValue()).startsWith("[orchestration_error] ").contains("maximum");
    }

    @Test
    void respondToMessage_whenProviderFails_recordsErrorAndRethrows() {
        stubLoadAndEchoAppend();
        when(registry.getProvider(PROVIDER)).thenReturn(Optional.of(provider));
        LlmRateLimitException failure = new LlmRateLimitException(PROVIDER, MODEL, "429 throttled");
        when(provider.invoke(any())).thenThrow(failure);

        assertThatThrownBy(() ->
                serviceWith(emptyRegistry()).respondToMessage(conversationId, triggerId))
                .isSameAs(failure);

        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(errorRecorder).recordError(eq(conversationId), content.capture());
        assertThat(content.getValue()).startsWith("[orchestration_error] LlmRateLimitException: ");
        verify(gateway, never()).append(any()); // no message persisted on failure
    }

    @Test
    void respondToMessage_whenProviderNotAvailable_throwsLlmProviderNotAvailable() {
        stubLoadAndEchoAppend();
        when(registry.getProvider(PROVIDER)).thenReturn(Optional.empty());
        when(registry.availableProviders()).thenReturn(Set.of());

        assertThatThrownBy(() ->
                serviceWith(emptyRegistry()).respondToMessage(conversationId, triggerId))
                .isInstanceOf(LlmProviderNotAvailableException.class);
        verifyNoInteractions(errorRecorder);
    }

    @Test
    void respondToMessage_whenLoadThrows_propagatesWithoutRecording() {
        when(gateway.load(conversationId, triggerId))
                .thenThrow(new ConversationNotFoundException("not visible"));

        assertThatThrownBy(() ->
                serviceWith(emptyRegistry()).respondToMessage(conversationId, triggerId))
                .isInstanceOf(ConversationNotFoundException.class);
        verifyNoInteractions(errorRecorder);
        verify(provider, never()).invoke(any());
    }

    // === helpers ===

    private OrchestratorService serviceWith(ToolRegistry toolRegistry) {
        return new OrchestratorService(gateway, registry, new ContextBuilder(), toolRegistry,
                new MockEnvironment(), errorRecorder);
    }

    private void stubLoadAndEchoAppend() {
        Agent agent = Agent.create(tenantId, "DentalBot", "You are helpful.", PROVIDER, MODEL);
        Message user = Message.from(conversationId, MessageRole.USER, "Hola");
        when(gateway.load(conversationId, triggerId))
                .thenReturn(new LoadedConversation(agent, List.of(user)));
        when(gateway.append(any())).thenAnswer(call -> call.getArgument(0));
    }

    private List<Message> capturedAppends(int times) {
        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(gateway, times(times)).append(captor.capture());
        return captor.getAllValues();
    }

    private static ToolRegistry emptyRegistry() {
        return new ToolRegistry(List.of());
    }

    private static ToolRegistry clockRegistry() {
        return new ToolRegistry(List.of(new ClockTool(Clock.fixed(FIXED, ZoneOffset.UTC))));
    }

    private static LlmResponse reply(String text) {
        return new LlmResponse(text, List.of(), FinishReason.STOP, LlmUsage.of(5, 5));
    }

    private static LlmResponse toolRequest(String toolName) {
        return new LlmResponse("", List.of(new ToolCall("call-1", toolName, Map.of())),
                FinishReason.TOOL_USE, LlmUsage.of(3, 4));
    }

    private static Tool throwingTool(String name) {
        return new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition(name, "always throws", Map.of("type", "object"));
            }

            @Override
            public ToolResult execute(ToolCall call) {
                throw new IllegalStateException("boom");
            }
        };
    }
}
