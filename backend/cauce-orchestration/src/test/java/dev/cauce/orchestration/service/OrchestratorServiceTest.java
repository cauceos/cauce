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
import dev.cauce.core.audit.AuditContentHash;
import dev.cauce.core.audit.AuditEvent;
import dev.cauce.core.conversation.AgentReplyDispatcher;
import dev.cauce.core.conversation.Conversation;
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
import dev.cauce.orchestration.audit.ConductAuditEvents;
import dev.cauce.orchestration.context.ContextBuilder;
import dev.cauce.orchestration.events.ContextAssembled;
import dev.cauce.orchestration.events.InvocationCompleted;
import dev.cauce.orchestration.events.LlmInvoked;
import dev.cauce.orchestration.events.LlmResponded;
import dev.cauce.orchestration.events.OrchestrationEvent;
import dev.cauce.orchestration.events.ToolCallRequested;
import dev.cauce.orchestration.events.ToolExecuted;
import dev.cauce.orchestration.exception.LlmProviderNotAvailableException;
import dev.cauce.orchestration.exception.MaxToolIterationsExceededException;
import dev.cauce.orchestration.service.ConversationGateway.LoadedConversation;
import dev.cauce.orchestration.usage.LlmUsageRecord;
import dev.cauce.orchestration.usage.LlmUsageRecorder;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.env.MockEnvironment;

class OrchestratorServiceTest {

    private static final String MODEL = "claude-sonnet-4-7";
    private static final String PROVIDER = "anthropic";
    private static final Instant FIXED = Instant.parse("2026-06-13T10:15:30Z");

    private final UUID invocationId = UUID.randomUUID();
    private final UUID conversationId = UUID.randomUUID();
    private final UUID tenantId = UUID.randomUUID();
    private final UUID triggerId = UUID.randomUUID();

    private ConversationGateway gateway;
    private LlmProviderRegistry registry;
    private OrchestrationErrorRecorder errorRecorder;
    private LlmUsageRecorder usageRecorder;
    private LlmProvider provider;
    private ApplicationEventPublisher eventPublisher;
    private AgentReplyDispatcher replyDispatcher;
    private ObjectProvider<AgentReplyDispatcher> replyDispatcherProvider;
    private Conversation conversation;
    private Agent agent;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        gateway = Mockito.mock(ConversationGateway.class);
        registry = Mockito.mock(LlmProviderRegistry.class);
        errorRecorder = Mockito.mock(OrchestrationErrorRecorder.class);
        usageRecorder = Mockito.mock(LlmUsageRecorder.class);
        provider = Mockito.mock(LlmProvider.class);
        when(provider.id()).thenReturn(PROVIDER);
        eventPublisher = Mockito.mock(ApplicationEventPublisher.class);
        replyDispatcher = Mockito.mock(AgentReplyDispatcher.class);
        replyDispatcherProvider = Mockito.mock(ObjectProvider.class);
        when(replyDispatcherProvider.getIfAvailable()).thenReturn(replyDispatcher);
    }

    @Test
    void respondToMessage_noToolsAvailable_persistsAgentReplyAndOffersNoTools() {
        stubLoadAndEchoAppend();
        when(registry.getProvider(PROVIDER)).thenReturn(Optional.of(provider));
        when(provider.invoke(any())).thenReturn(reply("Hola, soy un agente"));

        Message result = serviceWith(emptyRegistry()).respondToMessage(invocationId, conversationId, triggerId);

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

        serviceWith(emptyRegistry()).respondToMessage(invocationId, conversationId, triggerId);

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

        Message result = serviceWith(clockRegistry()).respondToMessage(invocationId, conversationId, triggerId);

        assertThat(result.role()).isEqualTo(MessageRole.AGENT);
        assertThat(result.content()).isEqualTo("It is 2026-06-13T10:15:30Z.");

        // Persisted in order: TOOL_CALL, TOOL_RESULT (the clock output) via plain appends;
        // the final AGENT reply goes through the audited append (asserted via the result).
        List<Message> persisted = capturedAppends(2);
        assertThat(persisted.get(0).role()).isEqualTo(MessageRole.TOOL_CALL);
        assertThat(persisted.get(1).role()).isEqualTo(MessageRole.TOOL_RESULT);
        ToolResult result1 = (ToolResult) persisted.get(1).toolContent().orElseThrow();
        assertThat(result1.output()).isEqualTo("2026-06-13T10:15:30Z");
        assertThat(result1.isError()).isFalse();
        verify(gateway).appendAudited(eq(result), any());
        verify(provider, times(2)).invoke(any());
        verifyNoInteractions(errorRecorder);
    }

    @Test
    void respondToMessage_whenToolThrows_feedsErrorBackAndCompletes() {
        stubLoadAndEchoAppend();
        when(registry.getProvider(PROVIDER)).thenReturn(Optional.of(provider));
        when(provider.invoke(any())).thenReturn(toolRequest("boom_tool"), reply("Recovered."));

        Message result = serviceWith(new ToolRegistry(List.of(throwingTool("boom_tool"))))
                .respondToMessage(invocationId, conversationId, triggerId);

        assertThat(result.content()).isEqualTo("Recovered."); // invocation completes, not failed
        ToolResult toolResult = (ToolResult) capturedAppends(2).get(1).toolContent().orElseThrow();
        assertThat(toolResult.isError()).isTrue();
        assertThat(toolResult.output()).contains("Tool execution failed");
        verifyNoInteractions(errorRecorder);
    }

    @Test
    void respondToMessage_whenToolUnknown_feedsErrorBackAndCompletes() {
        stubLoadAndEchoAppend();
        when(registry.getProvider(PROVIDER)).thenReturn(Optional.of(provider));
        when(provider.invoke(any())).thenReturn(toolRequest("does_not_exist"), reply("Done."));

        Message result = serviceWith(emptyRegistry()).respondToMessage(invocationId, conversationId, triggerId);

        assertThat(result.content()).isEqualTo("Done.");
        ToolResult toolResult = (ToolResult) capturedAppends(2).get(1).toolContent().orElseThrow();
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
                serviceWith(clockRegistry()).respondToMessage(invocationId, conversationId, triggerId))
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
                serviceWith(emptyRegistry()).respondToMessage(invocationId, conversationId, triggerId))
                .isSameAs(failure);

        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(errorRecorder).recordError(eq(conversationId), content.capture());
        assertThat(content.getValue()).startsWith("[orchestration_error] LlmRateLimitException: ");
        verify(gateway, never()).append(any()); // no message persisted on failure
        verify(gateway, never()).appendAudited(any(), any()); // and no audit record either
    }

    @Test
    void respondToMessage_whenProviderNotAvailable_throwsLlmProviderNotAvailable() {
        stubLoadAndEchoAppend();
        when(registry.getProvider(PROVIDER)).thenReturn(Optional.empty());
        when(registry.availableProviders()).thenReturn(Set.of());

        assertThatThrownBy(() ->
                serviceWith(emptyRegistry()).respondToMessage(invocationId, conversationId, triggerId))
                .isInstanceOf(LlmProviderNotAvailableException.class);
        verifyNoInteractions(errorRecorder);
    }

    @Test
    void respondToMessage_whenLoadThrows_propagatesWithoutRecording() {
        when(gateway.load(conversationId, triggerId))
                .thenThrow(new ConversationNotFoundException("not visible"));

        assertThatThrownBy(() ->
                serviceWith(emptyRegistry()).respondToMessage(invocationId, conversationId, triggerId))
                .isInstanceOf(ConversationNotFoundException.class);
        verifyNoInteractions(errorRecorder);
        verify(provider, never()).invoke(any());
    }

    @Test
    void respondToMessage_simpleReply_publishesLifecycleEventsInOrder() {
        stubLoadAndEchoAppend();
        when(registry.getProvider(PROVIDER)).thenReturn(Optional.of(provider));
        when(provider.invoke(any())).thenReturn(reply("Hola, soy un agente"));

        Message result = serviceWith(emptyRegistry())
                .respondToMessage(invocationId, conversationId, triggerId);

        List<OrchestrationEvent> events = publishedEvents();
        assertThat(events).hasExactlyElementsOfTypes(ContextAssembled.class, LlmInvoked.class,
                LlmResponded.class, InvocationCompleted.class);
        assertThat(events).allSatisfy(event ->
                assertThat(event.invocationId()).isEqualTo(invocationId));

        ContextAssembled assembled = (ContextAssembled) events.get(0);
        assertThat(assembled.roundIndex()).isZero();
        assertThat(assembled.modelName()).isEqualTo(MODEL);
        assertThat(assembled.messageCount()).isEqualTo(1);

        LlmInvoked invoked = (LlmInvoked) events.get(1);
        assertThat(invoked.provider()).isEqualTo(PROVIDER);
        assertThat(invoked.roundIndex()).isZero();

        LlmResponded responded = (LlmResponded) events.get(2);
        assertThat(responded.finishReason()).isEqualTo("STOP");
        assertThat(responded.inputTokens()).isEqualTo(5);
        assertThat(responded.outputTokens()).isEqualTo(5);
        assertThat(responded.totalTokens()).isEqualTo(10);

        InvocationCompleted completed = (InvocationCompleted) events.get(3);
        assertThat(completed.roundCount()).isEqualTo(1);
        assertThat(completed.finalMessageId()).isEqualTo(result.id());
    }

    @Test
    void respondToMessage_toolRound_publishesToolEventsWithRoundIndices() {
        stubLoadAndEchoAppend();
        when(registry.getProvider(PROVIDER)).thenReturn(Optional.of(provider));
        when(provider.invoke(any())).thenReturn(
                toolRequest("get_current_time"),
                reply("It is 2026-06-13T10:15:30Z."));

        serviceWith(clockRegistry()).respondToMessage(invocationId, conversationId, triggerId);

        List<OrchestrationEvent> events = publishedEvents();
        assertThat(events).hasExactlyElementsOfTypes(
                ContextAssembled.class, LlmInvoked.class, LlmResponded.class,   // round 0
                ToolCallRequested.class, ToolExecuted.class,
                ContextAssembled.class, LlmInvoked.class, LlmResponded.class,   // round 1
                InvocationCompleted.class);

        assertThat(((LlmResponded) events.get(2)).finishReason()).isEqualTo("TOOL_USE");

        ToolCallRequested requested = (ToolCallRequested) events.get(3);
        assertThat(requested.toolName()).isEqualTo("get_current_time");
        assertThat(requested.toolCallId()).isEqualTo("call-1");
        assertThat(requested.roundIndex()).isZero();

        ToolExecuted executed = (ToolExecuted) events.get(4);
        assertThat(executed.toolName()).isEqualTo("get_current_time");
        assertThat(executed.isError()).isFalse();
        assertThat(executed.durationMs()).isNotNegative();

        assertThat(((ContextAssembled) events.get(5)).roundIndex()).isEqualTo(1);
        assertThat(((InvocationCompleted) events.get(8)).roundCount()).isEqualTo(2);
    }

    @Test
    void respondToMessage_whenToolFails_publishesToolExecutedWithErrorFlag() {
        stubLoadAndEchoAppend();
        when(registry.getProvider(PROVIDER)).thenReturn(Optional.of(provider));
        when(provider.invoke(any())).thenReturn(toolRequest("boom_tool"), reply("Recovered."));

        serviceWith(new ToolRegistry(List.of(throwingTool("boom_tool"))))
                .respondToMessage(invocationId, conversationId, triggerId);

        ToolExecuted executed = publishedEvents().stream()
                .filter(ToolExecuted.class::isInstance).map(ToolExecuted.class::cast)
                .findFirst().orElseThrow();
        assertThat(executed.isError()).isTrue();
    }

    @Test
    void respondToMessage_whenProviderFails_publishesNoRespondedOrCompletedEvent() {
        stubLoadAndEchoAppend();
        when(registry.getProvider(PROVIDER)).thenReturn(Optional.of(provider));
        when(provider.invoke(any()))
                .thenThrow(new LlmRateLimitException(PROVIDER, MODEL, "429 throttled"));

        assertThatThrownBy(() -> serviceWith(emptyRegistry())
                .respondToMessage(invocationId, conversationId, triggerId))
                .isInstanceOf(LlmRateLimitException.class);

        // The call was announced but produced nothing: LlmInvoked yes, the rest is the
        // worker's InvocationFailed (not emitted from here).
        assertThat(publishedEvents())
                .hasExactlyElementsOfTypes(ContextAssembled.class, LlmInvoked.class);
    }

    // === USAGE RECORDING (per LLM call, before the LlmResponded event) ===

    @Test
    void respondToMessage_singleRound_recordsOneUsageRecordWithPayload() {
        stubLoadAndEchoAppend();
        when(registry.getProvider(PROVIDER)).thenReturn(Optional.of(provider));
        when(provider.invoke(any())).thenReturn(reply("Hola, soy un agente"));

        serviceWith(emptyRegistry()).respondToMessage(invocationId, conversationId, triggerId);

        ArgumentCaptor<LlmUsageRecord> captor = ArgumentCaptor.forClass(LlmUsageRecord.class);
        verify(usageRecorder).record(captor.capture());
        LlmUsageRecord record = captor.getValue();
        assertThat(record.tenantId()).isEqualTo(tenantId);
        assertThat(record.agentId()).isEqualTo(agent.id());
        assertThat(record.conversationId()).isEqualTo(conversationId);
        assertThat(record.invocationId()).isEqualTo(invocationId);
        assertThat(record.provider()).isEqualTo(PROVIDER);
        assertThat(record.model()).isEqualTo(MODEL);
        assertThat(record.roundIndex()).isZero();
        assertThat(record.inputTokens()).isEqualTo(5);
        assertThat(record.outputTokens()).isEqualTo(5);
        assertThat(record.totalTokens()).isEqualTo(10);
        assertThat(record.finishReason()).isEqualTo("STOP");
    }

    @Test
    void respondToMessage_withToolRound_recordsOneUsageRecordPerLlmCall() {
        stubLoadAndEchoAppend();
        when(registry.getProvider(PROVIDER)).thenReturn(Optional.of(provider));
        when(provider.invoke(any())).thenReturn(
                toolRequest("get_current_time"),
                reply("It is 2026-06-13T10:15:30Z."));

        serviceWith(clockRegistry()).respondToMessage(invocationId, conversationId, triggerId);

        ArgumentCaptor<LlmUsageRecord> captor = ArgumentCaptor.forClass(LlmUsageRecord.class);
        verify(usageRecorder, times(2)).record(captor.capture());
        List<LlmUsageRecord> records = captor.getAllValues();
        assertThat(records.get(0).roundIndex()).isZero();
        assertThat(records.get(0).finishReason()).isEqualTo("TOOL_USE");
        assertThat(records.get(0).totalTokens()).isEqualTo(7);
        assertThat(records.get(1).roundIndex()).isEqualTo(1);
        assertThat(records.get(1).finishReason()).isEqualTo("STOP");
        assertThat(records.get(1).totalTokens()).isEqualTo(10);
    }

    @Test
    void respondToMessage_whenProviderFails_recordsNoUsage() {
        stubLoadAndEchoAppend();
        when(registry.getProvider(PROVIDER)).thenReturn(Optional.of(provider));
        when(provider.invoke(any()))
                .thenThrow(new LlmRateLimitException(PROVIDER, MODEL, "429 throttled"));

        assertThatThrownBy(() -> serviceWith(emptyRegistry())
                .respondToMessage(invocationId, conversationId, triggerId))
                .isInstanceOf(LlmRateLimitException.class);

        // No response, no billed tokens reported: nothing to record for the failed call.
        verifyNoInteractions(usageRecorder);
    }

    // === AUDIT CAPTURE (conduct.agent.responded rides the final append's transaction) ===

    @Test
    void respondToMessage_finalReply_auditsAgentRespondedWithContentHashNotContent() {
        stubLoadAndEchoAppend();
        when(registry.getProvider(PROVIDER)).thenReturn(Optional.of(provider));
        when(provider.invoke(any())).thenReturn(reply("Hola, soy un agente"));

        Message result = serviceWith(emptyRegistry())
                .respondToMessage(invocationId, conversationId, triggerId);

        AuditEvent event = capturedFinalAudit();
        assertThat(event.tenantId()).isEqualTo(tenantId);
        assertThat(event.eventType()).isEqualTo(ConductAuditEvents.AGENT_RESPONDED);
        assertThat(event.payload())
                .containsEntry("invocation_id", invocationId.toString())
                .containsEntry("conversation_id", conversationId.toString())
                .containsEntry("message_id", result.id().toString())
                .containsEntry("agent_id", agent.id().toString())
                .containsEntry("finish_reason", "STOP")
                .containsEntry("rounds", 1)
                .containsEntry("content_hash",
                        AuditContentHash.of(tenantId, "Hola, soy un agente"))
                .containsEntry("content_length", 19);
        assertThat(event.payload().values()).doesNotContain("Hola, soy un agente");
    }

    @Test
    void respondToMessage_toolRound_auditsOnlyTheFinalReplyWithRoundCount() {
        stubLoadAndEchoAppend();
        when(registry.getProvider(PROVIDER)).thenReturn(Optional.of(provider));
        when(provider.invoke(any())).thenReturn(
                toolRequest("get_current_time"),
                reply("It is 2026-06-13T10:15:30Z."));

        serviceWith(clockRegistry()).respondToMessage(invocationId, conversationId, triggerId);

        // Intermediate tool messages go through the plain (unaudited) append; the single
        // audited append is the terminal conduct fact, carrying the real round count.
        AuditEvent event = capturedFinalAudit();
        assertThat(event.payload()).containsEntry("rounds", 2);
    }

    // === OUTBOUND DISPATCH (explicit port) ===

    @Test
    void respondToMessage_finalReply_dispatchesItThroughTheReplyPort() {
        stubLoadAndEchoAppend();
        when(registry.getProvider(PROVIDER)).thenReturn(Optional.of(provider));
        when(provider.invoke(any())).thenReturn(reply("Hola, soy un agente"));

        Message result = serviceWith(emptyRegistry())
                .respondToMessage(invocationId, conversationId, triggerId);

        verify(replyDispatcher).dispatchAgentReply(conversation, result);
    }

    @Test
    void respondToMessage_whenDispatcherThrows_stillReturnsThePersistedReply() {
        stubLoadAndEchoAppend();
        when(registry.getProvider(PROVIDER)).thenReturn(Optional.of(provider));
        when(provider.invoke(any())).thenReturn(reply("Hola"));
        Mockito.doThrow(new IllegalStateException("dispatcher bug"))
                .when(replyDispatcher).dispatchAgentReply(any(), any());

        Message result = serviceWith(emptyRegistry())
                .respondToMessage(invocationId, conversationId, triggerId);

        // The reply is already committed; a dispatcher bug must never fail the invocation.
        assertThat(result.content()).isEqualTo("Hola");
        verifyNoInteractions(errorRecorder);
    }

    @Test
    void respondToMessage_withoutADispatcherOnTheClasspath_completesNormally() {
        stubLoadAndEchoAppend();
        when(registry.getProvider(PROVIDER)).thenReturn(Optional.of(provider));
        when(provider.invoke(any())).thenReturn(reply("Hola"));
        when(replyDispatcherProvider.getIfAvailable()).thenReturn(null);

        Message result = serviceWith(emptyRegistry())
                .respondToMessage(invocationId, conversationId, triggerId);

        assertThat(result.content()).isEqualTo("Hola");
    }

    @Test
    void respondToMessage_whenProviderFails_neverDispatches() {
        stubLoadAndEchoAppend();
        when(registry.getProvider(PROVIDER)).thenReturn(Optional.of(provider));
        when(provider.invoke(any()))
                .thenThrow(new LlmRateLimitException(PROVIDER, MODEL, "429 throttled"));

        assertThatThrownBy(() -> serviceWith(emptyRegistry())
                .respondToMessage(invocationId, conversationId, triggerId))
                .isInstanceOf(LlmRateLimitException.class);

        verifyNoInteractions(replyDispatcher);
    }

    // === helpers ===

    private OrchestratorService serviceWith(ToolRegistry toolRegistry) {
        return new OrchestratorService(gateway, registry, new ContextBuilder(), toolRegistry,
                new MockEnvironment(), errorRecorder, usageRecorder, eventPublisher,
                replyDispatcherProvider);
    }

    /** All lifecycle events published so far, in publication order. */
    private List<OrchestrationEvent> publishedEvents() {
        ArgumentCaptor<OrchestrationEvent> captor =
                ArgumentCaptor.forClass(OrchestrationEvent.class);
        verify(eventPublisher, Mockito.atLeast(0)).publishEvent(captor.capture());
        return captor.getAllValues();
    }

    private void stubLoadAndEchoAppend() {
        agent = Agent.create(tenantId, "DentalBot", "You are helpful.", PROVIDER, MODEL);
        conversation = Conversation.start(agent.id(), "telegram", "987654321");
        Message user = Message.from(conversationId, MessageRole.USER, "Hola");
        when(gateway.load(conversationId, triggerId))
                .thenReturn(new LoadedConversation(conversation, agent, List.of(user)));
        when(gateway.append(any())).thenAnswer(call -> call.getArgument(0));
        when(gateway.appendAudited(any(), any())).thenAnswer(call -> call.getArgument(0));
    }

    private List<Message> capturedAppends(int times) {
        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(gateway, times(times)).append(captor.capture());
        return captor.getAllValues();
    }

    /** The single audited (final) append and its audit event. */
    private AuditEvent capturedFinalAudit() {
        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(gateway).appendAudited(any(), captor.capture());
        return captor.getValue();
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
