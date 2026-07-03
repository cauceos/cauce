package dev.cauce.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.cauce.core.conversation.Conversation;
import dev.cauce.core.message.Message;
import dev.cauce.core.message.MessageRole;
import dev.cauce.orchestration.events.InvocationRequested;
import dev.cauce.tenancy.ConversationService;
import dev.cauce.tenancy.MessageService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;

class InboundMessageServiceTest {

    private ConversationService conversationService;
    private MessageService messageService;
    private PendingInvocationService pendingInvocationService;
    private ApplicationEventPublisher eventPublisher;
    private InboundMessageService service;

    @BeforeEach
    void setUp() {
        conversationService = mock(ConversationService.class);
        messageService = mock(MessageService.class);
        pendingInvocationService = mock(PendingInvocationService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        service = new InboundMessageService(conversationService, messageService,
                pendingInvocationService, eventPublisher);
    }

    @Test
    void ingest_resolvesAppendsAndEnqueuesInOrder_returningTheThreeIds() {
        UUID agentId = UUID.randomUUID();
        Conversation conversation = Conversation.start(agentId, "api", "user-1");
        Message userMessage = Message.from(conversation.id(), MessageRole.USER, "Hola");
        PendingInvocation invocation =
                PendingInvocation.create(UUID.randomUUID(), conversation.id(), userMessage.id());

        when(conversationService.resolveOrStartConversation(agentId, "api", "user-1"))
                .thenReturn(conversation);
        when(messageService.appendMessage(conversation.id(), MessageRole.USER, "Hola"))
                .thenReturn(userMessage);
        when(pendingInvocationService.enqueueInvocation(conversation.id(), userMessage.id()))
                .thenReturn(invocation);

        InboundMessageResult result = service.ingest(agentId, "api", "user-1", "Hola");

        assertThat(result.conversationId()).isEqualTo(conversation.id());
        assertThat(result.messageId()).isEqualTo(userMessage.id());
        assertThat(result.invocationId()).isEqualTo(invocation.id());

        InOrder inOrder = inOrder(conversationService, messageService, pendingInvocationService);
        inOrder.verify(conversationService).resolveOrStartConversation(agentId, "api", "user-1");
        inOrder.verify(messageService).appendMessage(conversation.id(), MessageRole.USER, "Hola");
        inOrder.verify(pendingInvocationService).enqueueInvocation(conversation.id(), userMessage.id());
    }

    @Test
    void ingest_validMessage_publishesInvocationRequested() {
        UUID agentId = UUID.randomUUID();
        UUID owningTenantId = UUID.randomUUID();
        Conversation conversation = Conversation.start(agentId, "api", "user-1");
        Message userMessage = Message.from(conversation.id(), MessageRole.USER, "Hola");
        PendingInvocation invocation =
                PendingInvocation.create(owningTenantId, conversation.id(), userMessage.id());

        when(conversationService.resolveOrStartConversation(agentId, "api", "user-1"))
                .thenReturn(conversation);
        when(messageService.appendMessage(conversation.id(), MessageRole.USER, "Hola"))
                .thenReturn(userMessage);
        when(pendingInvocationService.enqueueInvocation(conversation.id(), userMessage.id()))
                .thenReturn(invocation);

        service.ingest(agentId, "api", "user-1", "Hola");

        ArgumentCaptor<InvocationRequested> captor =
                ArgumentCaptor.forClass(InvocationRequested.class);
        verify(eventPublisher).publishEvent(captor.capture());
        InvocationRequested event = captor.getValue();
        assertThat(event.invocationId()).isEqualTo(invocation.id());
        assertThat(event.tenantId()).isEqualTo(owningTenantId);
        assertThat(event.agentId()).isEqualTo(agentId);
        assertThat(event.conversationId()).isEqualTo(conversation.id());
        assertThat(event.messageId()).isEqualTo(userMessage.id());
        assertThat(event.occurredAt()).isNotNull();
    }

    @Test
    void ingest_forwardsTheCallerChannelType_soAdaptersAreNotTiedToApi() {
        UUID agentId = UUID.randomUUID();
        Conversation conversation = Conversation.start(agentId, "whatsapp", "+34600111222");
        Message userMessage = Message.from(conversation.id(), MessageRole.USER, "Hi");

        when(conversationService.resolveOrStartConversation(agentId, "whatsapp", "+34600111222"))
                .thenReturn(conversation);
        when(messageService.appendMessage(conversation.id(), MessageRole.USER, "Hi"))
                .thenReturn(userMessage);
        when(pendingInvocationService.enqueueInvocation(conversation.id(), userMessage.id()))
                .thenReturn(PendingInvocation.create(UUID.randomUUID(), conversation.id(), userMessage.id()));

        service.ingest(agentId, "whatsapp", "+34600111222", "Hi");

        verify(conversationService).resolveOrStartConversation(agentId, "whatsapp", "+34600111222");
    }
}
