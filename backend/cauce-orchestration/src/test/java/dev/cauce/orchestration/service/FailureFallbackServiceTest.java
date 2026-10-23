package dev.cauce.orchestration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.cauce.core.agent.Agent;
import dev.cauce.core.conversation.AgentReplyDispatcher;
import dev.cauce.core.conversation.Conversation;
import dev.cauce.core.conversation.ConversationNotFoundException;
import dev.cauce.core.identity.Identity;
import dev.cauce.core.identity.IdentityKind;
import dev.cauce.core.message.Message;
import dev.cauce.core.message.MessageRole;
import dev.cauce.orchestration.events.InvocationFailureType;
import dev.cauce.orchestration.service.ConversationGateway.ConversationAgent;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

class FailureFallbackServiceTest {

    private final UUID tenantId = UUID.randomUUID();

    private ConversationGateway gateway;
    private AgentReplyDispatcher dispatcher;
    private FailureFallbackService service;
    private Conversation conversation;
    private ApplicationEventPublisher eventPublisher;

    private static final UUID INVOCATION_ID = UUID.randomUUID();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        gateway = mock(ConversationGateway.class);
        dispatcher = mock(AgentReplyDispatcher.class);
        ObjectProvider<AgentReplyDispatcher> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(dispatcher);
        eventPublisher = mock(ApplicationEventPublisher.class);
        service = new FailureFallbackService(gateway, new FallbackReplyProperties(), provider, eventPublisher);
        conversation = Conversation.start(UUID.randomUUID(),
                Identity.create(tenantId, "telegram", IdentityKind.PROVIDER_USER_ID, "42"), UUID.randomUUID());
        when(gateway.append(any())).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void sendFallback_withoutAgentText_persistsAndDispatchesThePlatformDefault() {
        stubAgent(Agent.create(tenantId, "Bot", "p", "anthropic", "m"));

        service.sendFallback(INVOCATION_ID, conversation.id(), InvocationFailureType.LLM_ERROR);

        Message sent = dispatched();
        assertThat(sent.role()).isEqualTo(MessageRole.FALLBACK);
        assertThat(sent.content()).isEqualTo(FallbackReplyProperties.DEFAULT_REPLY);
        assertThat(sent.failureType()).contains("LLM_ERROR");
        verify(gateway).append(sent);
    }

    @Test
    void sendFallback_withAgentText_usesIt() {
        stubAgent(Agent.create(tenantId, "Bot", "p", "anthropic", "m").withFallbackReply("Vuelve en un rato."));

        service.sendFallback(INVOCATION_ID, conversation.id(), InvocationFailureType.MAX_TOOL_ITERATIONS);

        assertThat(dispatched().content()).isEqualTo("Vuelve en un rato.");
    }

    @Test
    void sendFallback_whenTheConversationCannotBeLoaded_isSwallowedAndSendsNothing() {
        when(gateway.loadConversationAndAgent(conversation.id()))
                .thenThrow(new ConversationNotFoundException("gone"));

        assertThatCode(() -> service.sendFallback(INVOCATION_ID, conversation.id(), InvocationFailureType.SETUP_ERROR))
                .doesNotThrowAnyException();
        verify(gateway, never()).append(any());
        verify(dispatcher, never()).dispatchAgentReply(any(), any());
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void sendFallback_onceRecorded_publishesWhichAgentCouldNotAnswer() {
        Agent agent = Agent.create(tenantId, "Bot", "p", "anthropic", "m");
        stubAgent(agent);

        service.sendFallback(INVOCATION_ID, conversation.id(), InvocationFailureType.LLM_RETRIES_EXHAUSTED);

        org.mockito.ArgumentCaptor<Object> event = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue()).isInstanceOfSatisfying(dev.cauce.orchestration.events.FallbackSent.class, sent -> {
            assertThat(sent.invocationId()).isEqualTo(INVOCATION_ID);
            assertThat(sent.tenantId()).isEqualTo(tenantId);
            assertThat(sent.agentId()).isEqualTo(agent.id());
            assertThat(sent.failureType()).isEqualTo(InvocationFailureType.LLM_RETRIES_EXHAUSTED);
        });
    }

    @Test
    void sendFallback_whenDispatchFails_isSwallowed_theFallbackStaysPersisted() {
        stubAgent(Agent.create(tenantId, "Bot", "p", "anthropic", "m"));
        org.mockito.Mockito.doThrow(new IllegalStateException("channel down"))
                .when(dispatcher).dispatchAgentReply(any(), any());

        assertThatCode(() -> service.sendFallback(INVOCATION_ID, conversation.id(), InvocationFailureType.EMPTY_REPLY))
                .doesNotThrowAnyException();
        verify(gateway).append(any());
    }

    private void stubAgent(Agent agent) {
        when(gateway.loadConversationAndAgent(conversation.id()))
                .thenReturn(new ConversationAgent(conversation, agent));
    }

    private Message dispatched() {
        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(dispatcher).dispatchAgentReply(org.mockito.ArgumentMatchers.eq(conversation), captor.capture());
        return captor.getValue();
    }
}
