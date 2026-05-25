package dev.cauce.orchestration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.cauce.core.agent.AgentNotFoundException;
import dev.cauce.core.agent.AgentStatus;
import dev.cauce.core.conversation.ConversationNotFoundException;
import dev.cauce.core.conversation.ConversationStatus;
import dev.cauce.core.message.MessageNotFoundException;
import dev.cauce.core.message.Message;
import dev.cauce.core.message.MessageRole;
import dev.cauce.llm.exception.LlmAuthenticationException;
import dev.cauce.llm.exception.LlmProviderException;
import dev.cauce.llm.exception.LlmRateLimitException;
import dev.cauce.llm.exception.LlmTimeoutException;
import dev.cauce.llm.model.FinishReason;
import dev.cauce.llm.model.LlmInvocation;
import dev.cauce.llm.model.LlmResponse;
import dev.cauce.llm.model.LlmUsage;
import dev.cauce.llm.spi.LlmProvider;
import dev.cauce.llm.spi.LlmProviderRegistry;
import dev.cauce.memory.agent.AgentEntity;
import dev.cauce.memory.agent.AgentMapper;
import dev.cauce.memory.agent.AgentRepository;
import dev.cauce.memory.conversation.ConversationEntity;
import dev.cauce.memory.conversation.ConversationRepository;
import dev.cauce.memory.message.MessageEntity;
import dev.cauce.memory.message.MessageMapper;
import dev.cauce.memory.message.MessageRepository;
import dev.cauce.orchestration.context.ContextBuilder;
import dev.cauce.orchestration.exception.InvalidTriggerMessageException;
import dev.cauce.orchestration.exception.LlmProviderNotAvailableException;
import dev.cauce.orchestration.exception.UnknownModelException;
import java.time.Instant;
import java.util.List;
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

    private final UUID conversationId = UUID.randomUUID();
    private final UUID agentId = UUID.randomUUID();
    private final UUID tenantId = UUID.randomUUID();
    private final UUID triggerId = UUID.randomUUID();
    private final Instant now = Instant.now();

    private ConversationRepository conversationRepository;
    private MessageRepository messageRepository;
    private AgentRepository agentRepository;
    private LlmProviderRegistry registry;
    private OrchestrationErrorRecorder errorRecorder;
    private LlmProvider provider;
    private OrchestratorService service;

    @BeforeEach
    void setUp() {
        conversationRepository = Mockito.mock(ConversationRepository.class);
        messageRepository = Mockito.mock(MessageRepository.class);
        agentRepository = Mockito.mock(AgentRepository.class);
        registry = Mockito.mock(LlmProviderRegistry.class);
        errorRecorder = Mockito.mock(OrchestrationErrorRecorder.class);
        provider = Mockito.mock(LlmProvider.class);
        service = new OrchestratorService(conversationRepository, messageRepository, new MessageMapper(),
                agentRepository, new AgentMapper(), registry, new ContextBuilder(), new MockEnvironment(),
                errorRecorder);
        when(messageRepository.save(any(MessageEntity.class))).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void respondToMessage_happyPath_invokesLlmPersistsAgentMessageAndReturnsIt() {
        stubConversation();
        stubHistory(triggerEntity(MessageRole.USER, "Hola"));
        stubAgent(MODEL);
        when(registry.getProvider(PROVIDER)).thenReturn(Optional.of(provider));
        when(provider.invoke(any(LlmInvocation.class))).thenReturn(
                new LlmResponse("Hola, soy un agente", List.of(), FinishReason.STOP, LlmUsage.of(10, 5)));

        Message result = service.respondToMessage(conversationId, triggerId);

        assertThat(result.role()).isEqualTo(MessageRole.AGENT);
        assertThat(result.content()).isEqualTo("Hola, soy un agente");
        verify(messageRepository).save(argThat(entity ->
                entity.getRole() == MessageRole.AGENT
                        && entity.getContent().equals("Hola, soy un agente")));
        verify(conversationRepository).touchLastMessageAt(eq(conversationId), any());
        verifyNoInteractions(errorRecorder);
    }

    @Test
    void respondToMessage_buildsInvocationFromAgentConfigAndContext() {
        stubConversation();
        stubHistory(triggerEntity(MessageRole.USER, "Hola"));
        stubAgent(MODEL);
        when(registry.getProvider(PROVIDER)).thenReturn(Optional.of(provider));
        when(provider.invoke(any(LlmInvocation.class))).thenReturn(
                new LlmResponse("ok", List.of(), FinishReason.STOP, LlmUsage.of(1, 1)));

        service.respondToMessage(conversationId, triggerId);

        ArgumentCaptor<LlmInvocation> captor = ArgumentCaptor.forClass(LlmInvocation.class);
        verify(provider).invoke(captor.capture());
        LlmInvocation invocation = captor.getValue();
        assertThat(invocation.modelName()).isEqualTo(MODEL);
        assertThat(invocation.systemPrompt()).isEqualTo("You are helpful.");
        assertThat(invocation.temperature()).isEqualTo(0.7);
        assertThat(invocation.maxTokens()).isEqualTo(4096);
        assertThat(invocation.messages()).extracting(m -> m.content()).containsExactly("Hola");
    }

    @Test
    void respondToMessage_whenConversationNotFound_throwsConversationNotFound() {
        when(conversationRepository.findById(conversationId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.respondToMessage(conversationId, triggerId))
                .isInstanceOf(ConversationNotFoundException.class);
        verifyNoInteractions(errorRecorder);
    }

    @Test
    void respondToMessage_whenTriggerMessageNotInConversation_throwsMessageNotFound() {
        stubConversation();
        when(messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId))
                .thenReturn(List.of()); // trigger absent

        assertThatThrownBy(() -> service.respondToMessage(conversationId, triggerId))
                .isInstanceOf(MessageNotFoundException.class);
    }

    @Test
    void respondToMessage_whenTriggerIsNotUserRole_throwsInvalidTriggerMessage() {
        stubConversation();
        stubHistory(triggerEntity(MessageRole.AGENT, "previous agent reply"));

        assertThatThrownBy(() -> service.respondToMessage(conversationId, triggerId))
                .isInstanceOf(InvalidTriggerMessageException.class);
    }

    @Test
    void respondToMessage_whenAgentNotFound_throwsAgentNotFound() {
        stubConversation();
        stubHistory(triggerEntity(MessageRole.USER, "Hola"));
        when(agentRepository.findById(agentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.respondToMessage(conversationId, triggerId))
                .isInstanceOf(AgentNotFoundException.class);
    }

    @Test
    void respondToMessage_whenModelUnknown_propagatesUnknownModelAndDoesNotRecordError() {
        stubConversation();
        stubHistory(triggerEntity(MessageRole.USER, "Hola"));
        stubAgent("gpt-4"); // not in ModelContextWindow table

        assertThatThrownBy(() -> service.respondToMessage(conversationId, triggerId))
                .isInstanceOf(UnknownModelException.class);
        verifyNoInteractions(errorRecorder);
    }

    @Test
    void respondToMessage_whenProviderNotAvailable_throwsLlmProviderNotAvailable() {
        stubConversation();
        stubHistory(triggerEntity(MessageRole.USER, "Hola"));
        stubAgent(MODEL);
        when(registry.getProvider(PROVIDER)).thenReturn(Optional.empty());
        when(registry.availableProviders()).thenReturn(Set.of());

        assertThatThrownBy(() -> service.respondToMessage(conversationId, triggerId))
                .isInstanceOf(LlmProviderNotAvailableException.class);
        verifyNoInteractions(errorRecorder);
    }

    @Test
    void respondToMessage_whenRateLimited_recordsErrorAndRethrows() {
        assertLlmFailureRecordedAndRethrown(
                new LlmRateLimitException(PROVIDER, MODEL, "429 throttled"), "LlmRateLimitException");
    }

    @Test
    void respondToMessage_whenTimeout_recordsErrorAndRethrows() {
        assertLlmFailureRecordedAndRethrown(
                new LlmTimeoutException(PROVIDER, MODEL, "timed out"), "LlmTimeoutException");
    }

    @Test
    void respondToMessage_whenAuthFails_recordsErrorAndRethrows() {
        assertLlmFailureRecordedAndRethrown(
                new LlmAuthenticationException(PROVIDER, MODEL, "401 unauthorized"),
                "LlmAuthenticationException");
    }

    private void assertLlmFailureRecordedAndRethrown(LlmProviderException failure,
                                                     String expectedClassInContent) {
        stubConversation();
        stubHistory(triggerEntity(MessageRole.USER, "Hola"));
        stubAgent(MODEL);
        when(registry.getProvider(PROVIDER)).thenReturn(Optional.of(provider));
        when(provider.invoke(any(LlmInvocation.class))).thenThrow(failure);

        assertThatThrownBy(() -> service.respondToMessage(conversationId, triggerId))
                .isSameAs(failure);

        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(errorRecorder).recordError(eq(conversationId), content.capture());
        assertThat(content.getValue())
                .startsWith("[orchestration_error] " + expectedClassInContent + ": ");
        verify(messageRepository, never()).save(any()); // no AGENT message on failure
    }

    // === fixtures ===

    private void stubConversation() {
        when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(
                new ConversationEntity(conversationId, agentId, "whatsapp", "+34600000000",
                        ConversationStatus.OPEN, now, now, null, null, null)));
    }

    private void stubHistory(MessageEntity... history) {
        when(messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId))
                .thenReturn(List.of(history));
    }

    private void stubAgent(String modelName) {
        when(agentRepository.findById(agentId)).thenReturn(Optional.of(
                new AgentEntity(agentId, tenantId, "DentalBot", "You are helpful.", PROVIDER, modelName,
                        0.7, 4096, AgentStatus.ACTIVE, now, now)));
    }

    private MessageEntity triggerEntity(MessageRole role, String content) {
        return new MessageEntity(triggerId, conversationId, role, content, now);
    }
}
