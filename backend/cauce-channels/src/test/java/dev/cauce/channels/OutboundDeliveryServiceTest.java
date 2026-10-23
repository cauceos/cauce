package dev.cauce.channels;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.cauce.channels.config.ChannelConfig;
import dev.cauce.channels.spi.ChannelAdapterRegistry;
import dev.cauce.channels.spi.ChannelDeliveryException;
import dev.cauce.channels.spi.ChannelOutboundMessage;
import dev.cauce.channels.spi.OutboundChannelAdapter;
import dev.cauce.core.conversation.Conversation;
import dev.cauce.core.identity.Identity;
import dev.cauce.core.identity.IdentityKind;
import dev.cauce.core.message.Message;
import dev.cauce.core.message.MessageRole;
import dev.cauce.core.tenant.TenantContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;

/**
 * Unit tests with a {@link SyncTaskExecutor} so the "async" delivery runs deterministically
 * on the test thread.
 */
class OutboundDeliveryServiceTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID AGENT_ID = UUID.randomUUID();

    private OutboundChannelAdapter telegramOutbound;
    private ChannelConfigService channelConfigService;
    private InterlocutorLookup interlocutorLookup;
    private OutboundDeliveryService service;
    private Identity interlocutor;

    private Conversation conversation;
    private Message reply;
    private ChannelConfig config;

    @BeforeEach
    void setUp() {
        telegramOutbound = mock(OutboundChannelAdapter.class);
        when(telegramOutbound.channelType()).thenReturn("telegram");
        channelConfigService = mock(ChannelConfigService.class);
        interlocutorLookup = mock(InterlocutorLookup.class);
        service = new OutboundDeliveryService(
                new ChannelAdapterRegistry(List.of(), List.of(telegramOutbound)),
                channelConfigService, interlocutorLookup, new SyncTaskExecutor(),
                dev.cauce.orchestration.tracing.MessageTracing.noop());
        interlocutor = Identity.create(TENANT_ID, "telegram", IdentityKind.PROVIDER_USER_ID, "987654321");
        when(interlocutorLookup.find(interlocutor.id())).thenReturn(Optional.of(interlocutor));
        config = ChannelConfig.create(TENANT_ID, AGENT_ID, "telegram", "12345:token", "hash");
        conversation = Conversation.start(AGENT_ID, interlocutor, config.id());
        reply = Message.from(conversation.id(), MessageRole.AGENT, "Hola, soy un agente");
        TenantContext.setCurrentTenantId(TENANT_ID);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void dispatchAgentReply_conversationWithoutConfig_isNoOp_withoutTouchingTheDatabase() {
        Identity apiIdentity = Identity.create(TENANT_ID, "api", IdentityKind.CLIENT_REFERENCE, "user-1");
        Conversation apiConversation = Conversation.start(AGENT_ID, apiIdentity, null);

        service.dispatchAgentReply(apiConversation, reply);

        verifyNoInteractions(channelConfigService, interlocutorLookup);
        verify(telegramOutbound, never()).deliver(any(), any());
    }

    @Test
    void dispatchAgentReply_deliversThroughTheConversationsOwnConfig_toItsIdentity() {
        AtomicReference<UUID> tenantDuringDelivery = new AtomicReference<>();
        when(channelConfigService.findActiveConfig(config.id())).thenReturn(Optional.of(config));
        doAnswer(invocation -> {
            tenantDuringDelivery.set(TenantContext.getCurrentTenantId().orElse(null));
            return null;
        }).when(telegramOutbound).deliver(any(), any());

        service.dispatchAgentReply(conversation, reply);

        verify(telegramOutbound).deliver(
                new ChannelOutboundMessage(IdentityKind.PROVIDER_USER_ID, "987654321", "Hola, soy un agente"),
                config);
        assertThat(tenantDuringDelivery.get()).isEqualTo(TENANT_ID);
        // The task cleared its own context; the dispatching thread ends clean (SyncTaskExecutor
        // runs it on the same thread here).
        assertThat(TenantContext.getCurrentTenantId()).isEmpty();
    }

    @Test
    void dispatchAgentReply_tellsTheAdapterWhenTheInterlocutorLastWrote() {
        java.time.Instant lastWrote = java.time.Instant.parse("2026-10-21T11:59:00Z");
        when(channelConfigService.findActiveConfig(config.id())).thenReturn(Optional.of(config));
        when(interlocutorLookup.lastMessageAt(conversation.id())).thenReturn(Optional.of(lastWrote));

        service.dispatchAgentReply(conversation, reply);

        verify(telegramOutbound).deliver(new ChannelOutboundMessage(IdentityKind.PROVIDER_USER_ID, "987654321",
                "Hola, soy un agente", lastWrote), config);
    }

    @Test
    void dispatchAgentReply_configNoLongerActive_skipsDelivery() {
        when(channelConfigService.findActiveConfig(config.id())).thenReturn(Optional.empty());

        service.dispatchAgentReply(conversation, reply);

        verify(telegramOutbound, never()).deliver(any(), any());
    }

    @Test
    void dispatchAgentReply_configsChannelHasNoOutboundAdapter_skipsDelivery() {
        ChannelConfig inboundOnly = ChannelConfig.create(TENANT_ID, AGENT_ID, "whatsapp", "token", "hash");
        Conversation viaInboundOnly = Conversation.start(AGENT_ID, interlocutor, inboundOnly.id());
        when(channelConfigService.findActiveConfig(inboundOnly.id())).thenReturn(Optional.of(inboundOnly));

        service.dispatchAgentReply(viaInboundOnly, reply);

        verify(telegramOutbound, never()).deliver(any(), any());
        verifyNoInteractions(interlocutorLookup);
    }

    @Test
    void dispatchAgentReply_identityNotVisible_skipsDelivery() {
        when(channelConfigService.findActiveConfig(config.id())).thenReturn(Optional.of(config));
        when(interlocutorLookup.find(interlocutor.id())).thenReturn(Optional.empty());

        service.dispatchAgentReply(conversation, reply);

        verify(telegramOutbound, never()).deliver(any(), any());
    }

    @Test
    void dispatchAgentReply_deliveryFailure_isSwallowed() {
        when(channelConfigService.findActiveConfig(config.id())).thenReturn(Optional.of(config));
        doThrow(new ChannelDeliveryException("Telegram is down"))
                .when(telegramOutbound).deliver(any(), any());

        assertThatCode(() -> service.dispatchAgentReply(conversation, reply))
                .doesNotThrowAnyException();
    }

    @Test
    void dispatchAgentReply_withoutTenantContext_skipsDelivery() {
        TenantContext.clear();

        service.dispatchAgentReply(conversation, reply);

        verify(telegramOutbound, never()).deliver(any(), any());
    }
}
