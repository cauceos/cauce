package dev.cauce.orchestration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.cauce.core.agent.Agent;
import dev.cauce.core.audit.AuditContentHash;
import dev.cauce.core.audit.AuditEvent;
import dev.cauce.core.audit.AuditToolHash;
import dev.cauce.core.conversation.AgentReplyDispatcher;
import dev.cauce.core.conversation.Conversation;
import dev.cauce.core.conversation.ConversationNotFoundException;
import dev.cauce.core.identity.Identity;
import dev.cauce.core.identity.IdentityKind;
import dev.cauce.core.message.Message;
import dev.cauce.core.message.MessageRole;
import dev.cauce.core.tool.ToolCall;
import dev.cauce.core.tool.ToolDefinition;
import dev.cauce.core.tool.ToolExecutionContext;
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
import dev.cauce.orchestration.context.TokenEstimator;
import dev.cauce.orchestration.events.ContextAssembled;
import dev.cauce.orchestration.events.InvocationCompleted;
import dev.cauce.orchestration.events.LlmFailed;
import dev.cauce.orchestration.events.LlmInvoked;
import dev.cauce.orchestration.events.LlmResponded;
import dev.cauce.orchestration.events.OrchestrationEvent;
import dev.cauce.orchestration.events.ToolCallRequested;
import dev.cauce.orchestration.events.ToolExecuted;
import dev.cauce.orchestration.exception.LlmProviderNotAvailableException;
import dev.cauce.orchestration.exception.EmptyReplyException;
import dev.cauce.orchestration.exception.MaxToolIterationsExceededException;
import dev.cauce.orchestration.exception.SpendLimitReachedException;
import dev.cauce.orchestration.exception.PromptDoesNotFitContextException;
import dev.cauce.orchestration.exception.UnpricedModelException;
import dev.cauce.orchestration.service.ConversationGateway.LoadedConversation;
import dev.cauce.orchestration.usage.LlmUsageRecord;
import dev.cauce.orchestration.usage.LlmUsageRecorder;
import dev.cauce.orchestration.usage.SpendGuard;
import dev.cauce.tools.clock.ClockTool;
import dev.cauce.tools.spi.Tool;
import dev.cauce.tools.spi.ToolArguments.InvalidToolArgumentException;
import dev.cauce.tools.spi.ToolRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
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
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-23T10:00:00Z"), ZoneOffset.UTC);

    private final MockEnvironment environment = new MockEnvironment();

    private final UUID invocationId = UUID.randomUUID();
    private final UUID conversationId = UUID.randomUUID();
    private final UUID tenantId = UUID.randomUUID();
    private final UUID triggerId = UUID.randomUUID();

    private ConversationGateway gateway;
    private LlmProviderRegistry registry;
    private OrchestrationErrorRecorder errorRecorder;
    private LlmUsageRecorder usageRecorder;
    private SpendGuard spendGuard;
    private LlmProvider provider;
    private ApplicationEventPublisher eventPublisher;
    private AgentReplyDispatcher replyDispatcher;
    private ObjectProvider<AgentReplyDispatcher> replyDispatcherProvider;
    private ObjectProvider<dev.cauce.core.business.profile.BusinessProfileSource> businessProfiles;
    private Conversation conversation;
    private Agent agent;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        gateway = Mockito.mock(ConversationGateway.class);
        registry = Mockito.mock(LlmProviderRegistry.class);
        errorRecorder = Mockito.mock(OrchestrationErrorRecorder.class);
        usageRecorder = Mockito.mock(LlmUsageRecorder.class);
        spendGuard = Mockito.mock(SpendGuard.class);
        when(spendGuard.check(any())).thenReturn(SpendGuard.Verdict.ALLOWED);
        provider = Mockito.mock(LlmProvider.class);
        when(provider.id()).thenReturn(PROVIDER);
        eventPublisher = Mockito.mock(ApplicationEventPublisher.class);
        replyDispatcher = Mockito.mock(AgentReplyDispatcher.class);
        replyDispatcherProvider = Mockito.mock(ObjectProvider.class);
        when(replyDispatcherProvider.getIfAvailable()).thenReturn(replyDispatcher);
        businessProfiles = Mockito.mock(ObjectProvider.class); // no business: no stamps
    }

    @Test
    void respondToMessage_forABusinessWithATimeZone_readsEachCustomerMessageWithWhenItWasSent() {
        stubLoadAndEchoAppend();
        Message older = Message.from(conversationId, MessageRole.USER, "Hola")
                .stampedAt(Instant.parse("2026-10-22T08:01:23Z"));
        Message today = Message.from(conversationId, MessageRole.USER, "Mesa para dos mañana")
                .stampedAt(Instant.parse("2026-10-24T16:40:44Z"));
        when(gateway.load(conversationId, triggerId))
                .thenReturn(new LoadedConversation(conversation, agent, List.of(older, today)));
        dev.cauce.core.business.profile.BusinessProfileSource profiles =
                Mockito.mock(dev.cauce.core.business.profile.BusinessProfileSource.class);
        dev.cauce.core.business.profile.BusinessProfile profile =
                Mockito.mock(dev.cauce.core.business.profile.BusinessProfile.class);
        when(profile.timeZone()).thenReturn(java.time.ZoneId.of("Europe/Madrid"));
        when(profiles.findProfile(tenantId)).thenReturn(Optional.of(profile));
        when(businessProfiles.getIfAvailable()).thenReturn(profiles);
        when(registry.getProvider(PROVIDER)).thenReturn(Optional.of(provider));
        when(provider.invoke(any())).thenReturn(reply("¿Para el domingo 25?"));

        serviceWith(emptyRegistry()).respondToMessage(invocationId, conversationId, triggerId);

        ArgumentCaptor<LlmInvocation> captor = ArgumentCaptor.forClass(LlmInvocation.class);
        verify(provider).invoke(captor.capture());
        assertThat(captor.getValue().messages()).extracting(LlmMessage::content).containsExactly(
                "[Sent Thursday 2026-10-22 10:01 Europe/Madrid] Hola",
                "[Sent Saturday 2026-10-24 18:40 Europe/Madrid] Mesa para dos mañana");
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
        // No business here: the message reads stamped in UTC, said so (ADR 0008, amendment).
        assertThat(captor.getValue().messages()).extracting(LlmMessage::content)
                .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .matches("\\[Sent \\w+ \\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2} UTC\\] Hola");
        assertThat(captor.getValue().cacheRetention()).isEqualTo(dev.cauce.core.agent.PromptCacheRetention.SHORT);
        verifyNoInteractions(errorRecorder);
    }

    @Test
    void respondToMessage_asksForTheAgentsPromptCacheRetention() {
        stubLoadAndEchoAppend();
        agent = agent.withPromptCacheRetention(dev.cauce.core.agent.PromptCacheRetention.LONG);
        when(gateway.load(conversationId, triggerId))
                .thenReturn(new LoadedConversation(conversation, agent,
                        List.of(Message.from(conversationId, MessageRole.USER, "Hola"))));
        when(registry.getProvider(PROVIDER)).thenReturn(Optional.of(provider));
        when(provider.invoke(any())).thenReturn(reply("ok"));

        serviceWith(emptyRegistry()).respondToMessage(invocationId, conversationId, triggerId);

        ArgumentCaptor<LlmInvocation> captor = ArgumentCaptor.forClass(LlmInvocation.class);
        verify(provider).invoke(captor.capture());
        assertThat(captor.getValue().cacheablePrefix()).isTrue();
        assertThat(captor.getValue().cacheRetention()).isEqualTo(dev.cauce.core.agent.PromptCacheRetention.LONG);
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
        // Regression: an agent that configures no temperature sends none (the model's default).
        assertThat(invocation.temperature()).isNull();
        assertThat(invocation.maxTokens()).isEqualTo(4096);
        // The agent's tools and prompt are a stable prefix: the provider may cache it.
        assertThat(invocation.cacheablePrefix()).isTrue();
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

        // Persisted in order: TOOL_CALL, TOOL_RESULT (the clock output), then the final AGENT
        // reply — each through the audited append.
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
    void respondToMessage_executesTheToolWithTheContextTheOrchestratorBuilt() {
        stubLoadAndEchoAppend();
        when(registry.getProvider(PROVIDER)).thenReturn(Optional.of(provider));
        when(provider.invoke(any())).thenReturn(toolRequest("boom_tool"), reply("Done."));
        List<ToolExecutionContext> seen = new java.util.ArrayList<>();
        Tool recording = new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("boom_tool", "records its context", Map.of("type", "object"));
            }

            @Override
            public ToolResult execute(ToolCall call, ToolExecutionContext context) {
                seen.add(context);
                return ToolResult.success(call.toolCallId(), call.toolName(), "ok");
            }
        };

        serviceWith(new ToolRegistry(List.of(recording))).respondToMessage(invocationId, conversationId, triggerId);

        // ADR 0007, B3: the subject of the action comes from the orchestrator, never the model.
        assertThat(seen).containsExactly(new ToolExecutionContext(tenantId, agent.id(), conversationId,
                conversation.identityId(), triggerId, invocationId));
    }

    @Test
    void respondToMessage_whenAToolRejectsItsArguments_feedsTheToolsOwnMessageBack() {
        stubLoadAndEchoAppend();
        when(registry.getProvider(PROVIDER)).thenReturn(Optional.of(provider));
        when(provider.invoke(any())).thenReturn(toolRequest("boom_tool"), reply("Fixed."));
        Tool strict = new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("boom_tool", "rejects its arguments", Map.of("type", "object"));
            }

            @Override
            public ToolResult execute(ToolCall call, ToolExecutionContext context) {
                throw new InvalidToolArgumentException("'date' is required");
            }
        };

        serviceWith(new ToolRegistry(List.of(strict))).respondToMessage(invocationId, conversationId, triggerId);

        ToolResult toolResult = (ToolResult) capturedAppends(2).get(1).toolContent().orElseThrow();
        assertThat(toolResult.isError()).isTrue();
        assertThat(toolResult.output()).isEqualTo("Invalid arguments: 'date' is required");
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
        // ADR 0005, C6: sanitized — the exception's own message never reaches the model.
        assertThat(toolResult.output()).isEqualTo(OrchestratorService.TOOL_FAILURE_OUTPUT);
        assertThat(toolResult.output()).doesNotContain("boom");
        verifyNoInteractions(errorRecorder);
    }

    @Test
    void respondToMessage_offersOnlyTheAgentsGrantedTools() {
        stubLoadAndEchoAppend();
        agent = agent.withTools(List.of()); // ADR 0005, C4: empty by default, not even the clock
        when(gateway.load(conversationId, triggerId))
                .thenReturn(new LoadedConversation(conversation, agent,
                        List.of(Message.from(conversationId, MessageRole.USER, "Hola"))));
        when(registry.getProvider(PROVIDER)).thenReturn(Optional.of(provider));
        when(provider.invoke(any())).thenReturn(toolRequest("get_current_time"), reply("Sin reloj."));

        serviceWith(clockRegistry()).respondToMessage(invocationId, conversationId, triggerId);

        ArgumentCaptor<LlmInvocation> captor = ArgumentCaptor.forClass(LlmInvocation.class);
        verify(provider, times(2)).invoke(captor.capture());
        assertThat(captor.getAllValues().get(0).tools()).isEmpty();
        // A call to a registered tool outside the grant is, to the model, an unknown tool.
        ToolResult toolResult = (ToolResult) capturedAppends(2).get(1).toolContent().orElseThrow();
        assertThat(toolResult.isError()).isTrue();
        assertThat(toolResult.output()).contains("Unknown tool");
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
    void respondToMessage_whenFinalReplyIsEmptyAfterATool_failsWithoutPersistingOrDispatchingIt() {
        stubLoadAndEchoAppend();
        when(registry.getProvider(PROVIDER)).thenReturn(Optional.of(provider));
        when(provider.invoke(any())).thenReturn(toolRequest("get_current_time"), reply("  "));

        assertThatThrownBy(() ->
                serviceWith(clockRegistry()).respondToMessage(invocationId, conversationId, triggerId))
                .isInstanceOf(EmptyReplyException.class);

        ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
        verify(errorRecorder).recordError(eq(conversationId), error.capture());
        assertThat(error.getValue()).startsWith("[orchestration_error] ").contains("empty reply");
        assertThat(capturedAudits()).extracting(AuditEvent::eventType)
                .doesNotContain(ConductAuditEvents.AGENT_RESPONDED); // nothing empty is the answer
        verify(replyDispatcher, never()).dispatchAgentReply(any(), any());
    }

    @Test
    void respondToMessage_capIsAPlatformSetting() {
        stubLoadAndEchoAppend();
        when(registry.getProvider(PROVIDER)).thenReturn(Optional.of(provider));
        when(provider.invoke(any())).thenReturn(toolRequest("get_current_time"));

        assertThatThrownBy(() ->
                serviceWith(clockRegistry(), 3).respondToMessage(invocationId, conversationId, triggerId))
                .isInstanceOf(MaxToolIterationsExceededException.class)
                .hasMessageContaining("maximum of 3");

        verify(provider, times(3)).invoke(any());
    }

    @Test
    void respondToMessage_onARetry_countsTheRoundsAnEarlierAttemptAlreadySpent() {
        // A first attempt spent two tool rounds on this trigger, then the provider failed. The
        // retry has one round left of a cap of 3: the cap is per inbound message, not per attempt.
        agent = Agent.create(tenantId, "DentalBot", "You are helpful.", PROVIDER, MODEL)
                .withTools(List.of("get_current_time", "boom_tool"));
        conversation = Conversation.start(agent.id(),
                Identity.create(tenantId, "telegram", IdentityKind.PROVIDER_USER_ID, "987654321"), UUID.randomUUID());
        Message trigger = Message.from(conversationId, MessageRole.USER, "Hola");
        ToolCall call = new ToolCall("call-1", "get_current_time", Map.of());
        ToolResult result = ToolResult.success("call-1", "get_current_time", "10:15");
        List<Message> history = List.of(trigger,
                Message.toolCall(conversationId, call), Message.toolResult(conversationId, result),
                Message.toolCall(conversationId, call), Message.toolResult(conversationId, result));
        when(gateway.load(conversationId, trigger.id()))
                .thenReturn(new LoadedConversation(conversation, agent, history));
        when(gateway.append(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(registry.getProvider(PROVIDER)).thenReturn(Optional.of(provider));
        when(provider.invoke(any())).thenReturn(toolRequest("get_current_time"));

        assertThatThrownBy(() ->
                serviceWith(clockRegistry(), 3).respondToMessage(invocationId, conversationId, trigger.id()))
                .isInstanceOf(MaxToolIterationsExceededException.class);

        verify(provider, times(1)).invoke(any());
    }

    @Test
    void roundsAlreadyUsed_countsToolCallBatchesAfterTheTriggerOnly() {
        Message before = Message.from(conversationId, MessageRole.USER, "earlier");
        ToolCall call = new ToolCall("c", "get_current_time", Map.of());
        ToolResult result = ToolResult.success("c", "get_current_time", "t");
        Message trigger = Message.from(conversationId, MessageRole.USER, "now");
        List<Message> history = List.of(
                before, Message.toolCall(conversationId, call), Message.toolResult(conversationId, result),
                trigger,
                Message.from(conversationId, MessageRole.AGENT, "let me check"),
                Message.toolCall(conversationId, call), Message.toolCall(conversationId, call), // one batch
                Message.toolResult(conversationId, result), Message.toolResult(conversationId, result),
                Message.toolCall(conversationId, call), Message.toolResult(conversationId, result));

        assertThat(OrchestratorService.roundsAlreadyUsed(history, trigger.id())).isEqualTo(2);
        assertThat(OrchestratorService.roundsAlreadyUsed(history, UUID.randomUUID())).isZero();
    }

    @Test
    void respondToMessage_whenModelNeverStopsCallingTools_failsAtTheCap() {
        stubLoadAndEchoAppend();
        when(registry.getProvider(PROVIDER)).thenReturn(Optional.of(provider));
        when(provider.invoke(any())).thenReturn(toolRequest("get_current_time"));

        assertThatThrownBy(() ->
                serviceWith(clockRegistry()).respondToMessage(invocationId, conversationId, triggerId))
                .isInstanceOf(MaxToolIterationsExceededException.class);

        verify(provider, times(AgenticLoopProperties.DEFAULT_MAX_ITERATIONS)).invoke(any());
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
                .isInstanceOf(LlmProviderNotAvailableException.class)
                .hasMessageContaining("'anthropic' is not configured")
                .hasMessageContaining("ANTHROPIC_API_KEY is not set");
        verifyNoInteractions(errorRecorder);
    }

    @Test
    void respondToMessage_whenKeyIsSetButNoProviderRegistered_saysSoInsteadOfAMissingKey() {
        stubLoadAndEchoAppend();
        environment.setProperty("ANTHROPIC_API_KEY", "sk-ant-test");
        when(registry.getProvider(PROVIDER)).thenReturn(Optional.empty());
        when(registry.availableProviders()).thenReturn(Set.of("ollama"));

        assertThatThrownBy(() ->
                serviceWith(emptyRegistry()).respondToMessage(invocationId, conversationId, triggerId))
                .isInstanceOf(LlmProviderNotAvailableException.class)
                .hasMessageContaining("ANTHROPIC_API_KEY is set, but no adapter registered")
                .hasMessageNotContaining("sk-ant-test")
                .hasMessageContaining("[ollama]");
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
    void respondToMessage_providerServesItsOwnWindow_sizesToItNotToTheModel() {
        // Ollama serves 4096 tokens whatever the model could hold: assuming the model's window
        // (or the 16K fallback) would send what the server silently cuts.
        stubLoadAndEchoAppend();
        when(registry.getProvider(PROVIDER)).thenReturn(Optional.of(provider));
        when(provider.servedContextWindow(MODEL)).thenReturn(OptionalInt.of(4096));
        when(provider.invoke(any())).thenReturn(reply("ok"));

        serviceWith(emptyRegistry()).respondToMessage(invocationId, conversationId, triggerId);

        ContextAssembled assembled = (ContextAssembled) publishedEvents().get(0);
        assertThat(assembled.contextWindowLimit()).isEqualTo(4096);
        ArgumentCaptor<LlmInvocation> captor = ArgumentCaptor.forClass(LlmInvocation.class);
        verify(provider).invoke(captor.capture());
        // History gets half of the window and the response the other half: together they fit.
        assertThat(captor.getValue().maxTokens()).isEqualTo(2048);
        assertThat(assembled.estimatedTokens() + captor.getValue().maxTokens()).isLessThanOrEqualTo(4096);
    }

    @Test
    void respondToMessage_promptThatDoesNotFitTheServedWindow_failsBeforeTheModelIsCalled() {
        stubLoadAndEchoAppend();
        agent = Agent.create(tenantId, "DentalBot", "p".repeat(7168), PROVIDER, MODEL); // 2048 tokens
        when(gateway.load(conversationId, triggerId)).thenReturn(new LoadedConversation(conversation, agent,
                List.of(Message.from(conversationId, MessageRole.USER, "Hola"))));
        when(registry.getProvider(PROVIDER)).thenReturn(Optional.of(provider));
        when(provider.servedContextWindow(MODEL)).thenReturn(OptionalInt.of(4096));

        assertThatThrownBy(() -> serviceWith(emptyRegistry()).respondToMessage(invocationId, conversationId, triggerId))
                .isInstanceOf(PromptDoesNotFitContextException.class)
                .hasMessageContaining("context window of 4096 tokens");
        // Never sent for the provider to cut silently.
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
        // What the budget estimated for this call: the history plus the system prompt (no tool registered).
        assertThat(responded.estimatedInputTokens())
                .isEqualTo(assembled.estimatedTokens() + TokenEstimator.estimate("You are helpful."));

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

        // The call was announced and failed: LlmInvoked then LlmFailed; the rest is the
        // worker's InvocationFailed (not emitted from here).
        assertThat(publishedEvents())
                .hasExactlyElementsOfTypes(ContextAssembled.class, LlmInvoked.class, LlmFailed.class);
        assertThat(publishedEvents().get(2)).isInstanceOfSatisfying(LlmFailed.class, failed ->
                assertThat(failed.errorType()).isEqualTo("LlmRateLimitException"));
    }

    // === SPEND LIMIT (ADR 0010, S6, S7: checked before the model is called) ===

    @Test
    void respondToMessage_whenTheSpendLimitIsReached_recordsAnErrorAndNeverCallsTheModel() {
        stubLoadAndEchoAppend();
        when(registry.getProvider(PROVIDER)).thenReturn(Optional.of(provider));
        when(spendGuard.check(any())).thenReturn(SpendGuard.Verdict.LIMIT_REACHED);

        assertThatThrownBy(() -> serviceWith(emptyRegistry()).respondToMessage(invocationId, conversationId, triggerId))
                .isInstanceOf(SpendLimitReachedException.class);

        verify(provider, never()).invoke(any());
        verify(errorRecorder).recordError(eq(conversationId), contains("spend limit"));
        verifyNoInteractions(usageRecorder);
    }

    @Test
    void respondToMessage_whenTheModelHasNoPriceUnderALimit_recordsAnErrorAndNeverCallsTheModel() {
        stubLoadAndEchoAppend();
        when(registry.getProvider(PROVIDER)).thenReturn(Optional.of(provider));
        when(spendGuard.check(any())).thenReturn(SpendGuard.Verdict.UNPRICED_MODEL);

        assertThatThrownBy(() -> serviceWith(emptyRegistry()).respondToMessage(invocationId, conversationId, triggerId))
                .isInstanceOf(UnpricedModelException.class);

        verify(provider, never()).invoke(any());
        verify(errorRecorder).recordError(eq(conversationId), contains(MODEL));
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
        // ADR 0008: the day and month a call is billed to come from the application clock.
        assertThat(record.createdAt()).isEqualTo(CLOCK.instant());
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
                        textHash(tenantId, "Hola, soy un agente"))
                .containsEntry("content_length", 19);
        assertThat(event.payload().values()).doesNotContain("Hola, soy un agente");
    }

    @Test
    void respondToMessage_toolRound_auditsTheCallTheResultAndTheFinalReply_byHashOnly() {
        stubLoadAndEchoAppend();
        when(registry.getProvider(PROVIDER)).thenReturn(Optional.of(provider));
        when(provider.invoke(any())).thenReturn(
                new LlmResponse("", List.of(new ToolCall("call-7", "get_current_time", Map.of("tz", "Europe/Madrid"))),
                        FinishReason.TOOL_USE, LlmUsage.of(3, 4)),
                reply("It is 2026-06-13T10:15:30Z."));

        serviceWith(clockRegistry()).respondToMessage(invocationId, conversationId, triggerId);

        // ADR 0005, C5: one record per tool call and per result, then the terminal reply.
        List<AuditEvent> audits = capturedAudits();
        assertThat(audits).extracting(AuditEvent::eventType).containsExactly(
                ConductAuditEvents.TOOL_CALLED, ConductAuditEvents.TOOL_RETURNED, ConductAuditEvents.AGENT_RESPONDED);
        assertThat(audits.get(0).payload())
                .containsEntry("tool_name", "get_current_time")
                .containsEntry("tool_call_id", "call-7")
                .containsEntry("round", 0)
                .containsEntry("arguments_hash", AuditToolHash.of(tenantId, Map.of("tz", "Europe/Madrid")));
        assertThat(audits.get(1).payload())
                .containsEntry("tool_call_id", "call-7")
                .containsEntry("outcome", "success")
                .containsEntry("output_hash", AuditToolHash.of(tenantId, "2026-06-13T10:15:30Z"))
                .containsKey("duration_ms");
        // Arguments and output never enter in clear.
        assertThat(audits.get(0).payload().values()).doesNotContain("Europe/Madrid", Map.of("tz", "Europe/Madrid"));
        assertThat(audits.get(1).payload().values()).doesNotContain("2026-06-13T10:15:30Z");
        assertThat(capturedFinalAudit().payload()).containsEntry("rounds", 2);
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
        return serviceWith(toolRegistry, AgenticLoopProperties.DEFAULT_MAX_ITERATIONS);
    }

    private OrchestratorService serviceWith(ToolRegistry toolRegistry, int maxIterations) {
        AgenticLoopProperties loop = new AgenticLoopProperties();
        loop.setMaxIterations(maxIterations);
        return new OrchestratorService(gateway, registry, new ContextBuilder(), toolRegistry,
                environment, errorRecorder, usageRecorder, eventPublisher,
                replyDispatcherProvider, loop, spendGuard,
                dev.cauce.orchestration.tracing.MessageTracing.noop(), CLOCK, passThroughVoiceNotes(),
                businessProfiles);
    }

    /** No voice note to transcribe: the conversation goes on as loaded. */
    private static dev.cauce.orchestration.voice.VoiceNoteTranscription passThroughVoiceNotes() {
        dev.cauce.orchestration.voice.VoiceNoteTranscription voiceNotes =
                Mockito.mock(dev.cauce.orchestration.voice.VoiceNoteTranscription.class);
        when(voiceNotes.transcribePending(any(), any(), any(), any(), any()))
                .thenAnswer(call -> call.getArgument(4));
        return voiceNotes;
    }

    /** All lifecycle events published so far, in publication order. */
    private List<OrchestrationEvent> publishedEvents() {
        ArgumentCaptor<OrchestrationEvent> captor =
                ArgumentCaptor.forClass(OrchestrationEvent.class);
        verify(eventPublisher, Mockito.atLeast(0)).publishEvent(captor.capture());
        return captor.getAllValues();
    }

    private void stubLoadAndEchoAppend() {
        agent = Agent.create(tenantId, "DentalBot", "You are helpful.", PROVIDER, MODEL)
                .withTools(List.of("get_current_time", "boom_tool"));
        conversation = Conversation.start(agent.id(),
                Identity.create(tenantId, "telegram", IdentityKind.PROVIDER_USER_ID, "987654321"), UUID.randomUUID());
        Message user = Message.from(conversationId, MessageRole.USER, "Hola");
        when(gateway.load(conversationId, triggerId))
                .thenReturn(new LoadedConversation(conversation, agent, List.of(user)));
        when(gateway.append(any())).thenAnswer(call -> call.getArgument(0));
        when(gateway.appendAudited(any(), any())).thenAnswer(call -> call.getArgument(0));
    }

    /**
     * The tool messages persisted, in order. Since ADR 0005 (C5) each one goes through the audited
     * append with its own conduct record, like the final reply.
     */
    private List<Message> capturedAppends(int times) {
        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(gateway, Mockito.atLeastOnce()).appendAudited(captor.capture(), any());
        List<Message> toolMessages = captor.getAllValues().stream()
                .filter(m -> m.role() == MessageRole.TOOL_CALL || m.role() == MessageRole.TOOL_RESULT)
                .toList();
        assertThat(toolMessages).hasSize(times);
        return toolMessages;
    }

    /** Every audit record written with an append, in order. */
    private List<AuditEvent> capturedAudits() {
        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(gateway, Mockito.atLeastOnce()).appendAudited(any(), captor.capture());
        return captor.getAllValues();
    }

    /** The audit event of the final reply (the last audited append). */
    private AuditEvent capturedFinalAudit() {
        List<AuditEvent> audits = capturedAudits();
        AuditEvent last = audits.get(audits.size() - 1);
        assertThat(last.eventType()).isEqualTo(ConductAuditEvents.AGENT_RESPONDED);
        return last;
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
            public ToolResult execute(ToolCall call, ToolExecutionContext context) {
                throw new IllegalStateException("boom");
            }
        };
    }
    @Test
    void turnLatency_isFromThePersonsMessageToTheReply_byTheirDatabaseStamps() {
        UUID conversationId = UUID.randomUUID();
        Message asked = Message.from(conversationId, MessageRole.USER, "hola")
                .stampedAt(Instant.parse("2026-10-23T10:00:00Z"));
        Message reply = Message.from(conversationId, MessageRole.AGENT, "buenas")
                .stampedAt(Instant.parse("2026-10-23T10:00:07.250Z"));

        assertThat(OrchestratorService.turnLatency(List.of(asked), asked.id(), reply))
                .isEqualTo(java.time.Duration.ofMillis(7250));
    }

    @Test
    void turnLatency_whenThePersonsMessageIsNotLoaded_isNull() {
        UUID conversationId = UUID.randomUUID();
        Message reply = Message.from(conversationId, MessageRole.AGENT, "buenas")
                .stampedAt(Instant.parse("2026-10-23T10:00:07Z"));

        assertThat(OrchestratorService.turnLatency(List.of(), UUID.randomUUID(), reply)).isNull();
    }

    /** The content hash v2 of a message of one text part, what the conduct payloads carry. */
    private static String textHash(java.util.UUID tenantId, String text) {
        return AuditContentHash.ofParts(tenantId,
                java.util.List.of(new dev.cauce.core.message.MessagePart.Text(text)));
    }
}
